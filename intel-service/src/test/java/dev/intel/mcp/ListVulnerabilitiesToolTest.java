package dev.intel.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.feed.FeedDocumentReader;
import dev.intel.feed.FeedEntry;
import dev.intel.feed.FeedItemNormalizer;
import dev.intel.feed.Fixtures;
import dev.intel.feed.SectionSplitter;
import dev.intel.ingest.IngestionService;
import dev.intel.store.IngestionOutcome;
import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionRunRepository;
import dev.intel.store.IngestionTrigger;
import dev.intel.store.VulnerabilityRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** The tool exactly as a consumer sees it: a real server on a real port, spoken to with the MCP SDK client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-tool;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false",
        "intel.schedule.enabled=false"
})
class ListVulnerabilitiesToolTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    VulnerabilityRepository vulnerabilities;

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    JdbcClient jdbc;

    private McpSyncClient client;

    @BeforeEach
    void connect() throws Exception {
        jdbc.sql("DELETE FROM vulnerability").update();
        jdbc.sql("DELETE FROM ingestion_run").update();
        FeedItemNormalizer normalizer = new FeedItemNormalizer(new SectionSplitter());
        Instant now = Instant.now();
        for (FeedEntry entry : new FeedDocumentReader().read(Fixtures.feedBytes())) {
            vulnerabilities.upsert(normalizer.normalize(IngestionService.SOURCE_NAME, entry), now);
        }
        runs.insert(new IngestionRun(UUID.randomUUID().toString(), IngestionService.SOURCE_NAME,
                IngestionTrigger.SCHEDULED, now.minusSeconds(3), now.minusSeconds(1), IngestionOutcome.SUCCESS, 8, 8,
                0, 0, 0, null));

        client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
                .endpoint("/mcp").build()).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        client.closeGracefully();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Map<String, Object> arguments) throws Exception {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("listVulnerabilities",
                arguments));
        assertThat(result.isError()).as("tool error: %s", result.content()).isNotEqualTo(Boolean.TRUE);
        return JSON.readValue(textOf(result), Map.class);
    }

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream().filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text()).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("records");
    }

    // ------------------------------------------------------------------ what the server offers

    @Test
    void offersExactlyOneReadOnlyToolAndNothingElse() {
        McpSchema.ListToolsResult tools = client.listTools();

        assertThat(tools.tools()).hasSize(1);
        McpSchema.Tool tool = tools.tools().get(0);
        assertThat(tool.name()).isEqualTo("listVulnerabilities");
        assertThat(tool.description()).contains("untrusted").contains("nextCursor");
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        assertThat(tool.annotations().destructiveHint()).isFalse();
        assertThat(tool.annotations().idempotentHint()).isTrue();
        Map<String, Object> schema = tool.inputSchema();
        assertThat(schema.get("type")).isEqualTo("object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsOnlyKeys("scope", "sinceDays", "cveId", "product", "pageSize", "cursor");
        Object required = schema.get("required");
        assertThat(required == null || ((List<?>) required).isEmpty()).as("every argument is optional").isTrue();

        McpSchema.ServerCapabilities capabilities = client.getServerCapabilities();
        assertThat(capabilities.tools()).isNotNull();
        assertThat(capabilities.resources()).isNull();
        assertThat(capabilities.prompts()).isNull();
    }

    @Test
    void aToolThatWouldChangeDataDoesNotExist() {
        for (String name : List.of("deleteVulnerability", "ingest", "runIngestion", "executeSql", "query")) {
            McpSchema.CallToolResult result = callUnchecked(name, Map.of());
            assertThat(result == null || Boolean.TRUE.equals(result.isError())).as(name).isTrue();
        }
    }

    private McpSchema.CallToolResult callUnchecked(String name, Map<String, Object> arguments) {
        try {
            return client.callTool(new McpSchema.CallToolRequest(name, arguments));
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ answers

    @Test
    void returnsTheDocumentedShapeForTheWholeStore() throws Exception {
        Map<String, Object> answer = call(Map.of("scope", "all"));

        assertThat(answer).containsOnlyKeys("records", "nextCursor", "freshness", "notice");
        assertThat(records(answer)).hasSize(8);
        assertThat(answer.get("nextCursor")).isNull();
        assertThat((String) answer.get("notice")).contains("untrusted");
        @SuppressWarnings("unchecked")
        Map<String, Object> freshness = (Map<String, Object>) answer.get("freshness");
        assertThat(freshness).containsOnlyKeys("lastSuccessfulIngestionAt", "stale", "status");
        assertThat(freshness.get("status")).isEqualTo("OK");
        assertThat(freshness.get("stale")).isEqualTo(false);
        assertThat(freshness.get("lastSuccessfulIngestionAt")).isNotNull();

        Map<String, Object> first = records(answer).get(0);
        assertThat(first).containsKeys("id", "sourceItemId", "title", "sourceUrl", "publishedAt", "lastReviewedAt",
                "firstSeenAt", "updatedAt", "summary", "sections", "cveIds", "tags", "evidenceLinks");
        assertThat(first.get("sourceItemId")).isEqualTo("sshole@2026-11-05");
        assertThat((String) first.get("publishedAt")).startsWith("2026-11-05T00:00:00");
        assertThat(first.get("cveIds")).isEqualTo(List.of("CVE-2026-123158"));
    }

    @Test
    void theRawFeedItemIsNeverPartOfTheAnswer() throws Exception {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("listVulnerabilities",
                Map.of("scope", "all")));

        String text = textOf(result);
        assertThat(text).doesNotContain("<![CDATA[").doesNotContain("rawPayload").doesNotContain("rawXml")
                .doesNotContain("contentSha256");
    }

    @Test
    void recentWithAWindowFiltersByPublishedOrReviewedTime() throws Exception {
        // the fixture's dates are in 2026; the real clock is later, so a short window finds nothing and a long one does
        Map<String, Object> none = call(Map.of("scope", "recent", "sinceDays", 1));

        assertThat(records(none)).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> freshness = (Map<String, Object>) none.get("freshness");
        assertThat(freshness.get("status")).as("an empty answer still says the data is fresh").isEqualTo("OK");

        assertThat(records(call(Map.of("scope", "recent", "sinceDays", 365)))).isNotEmpty();
    }

    @Test
    void filtersByCveAndProduct() throws Exception {
        assertThat(records(call(Map.of("cveId", "CVE-2026-123158"))))
                .extracting(r -> r.get("sourceItemId")).containsExactly("sshole@2026-11-05");
        assertThat(records(call(Map.of("product", "brotli"))))
                .extracting(r -> r.get("sourceItemId")).containsExactly("node2shell@2026-10-25");
        assertThat(records(call(Map.of("scope", "all", "cveId", "cve-2026-98432", "product", "kernel"))))
                .extracting(r -> r.get("sourceItemId")).containsExactly("linux-ipv4-tcp-use-after-free@2026-11-01");
    }

    @Test
    void pagesThroughEverythingWithTheCursor() throws Exception {
        Set<Object> seen = new HashSet<>();
        List<Object> order = new ArrayList<>();
        Object cursor = null;
        int pages = 0;
        do {
            Map<String, Object> arguments = new java.util.HashMap<>(Map.of("pageSize", 3));
            if (cursor != null) {
                arguments.put("cursor", cursor);
            }
            Map<String, Object> page = call(arguments);
            records(page).forEach(r -> {
                order.add(r.get("sourceItemId"));
                seen.add(r.get("sourceItemId"));
            });
            cursor = page.get("nextCursor");
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(order).hasSize(8).hasSameSizeAs(seen);
    }

    @Test
    void aHugePageSizeIsReducedAndAnEmptyCallUsesTheDefaults() throws Exception {
        assertThat(records(call(Map.of("pageSize", 500)))).hasSize(8);
        assertThat(records(call(Map.of()))).hasSize(8);
    }

    // ------------------------------------------------------------------ errors

    private String errorText(Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("listVulnerabilities",
                arguments));
        assertThat(result.isError()).as("expected a tool error for %s", arguments).isTrue();
        return textOf(result);
    }

    @Test
    void invalidArgumentsComeBackAsToolErrorsNamingTheArgumentAndReturningNoRecords() {
        assertThat(errorText(Map.of("scope", "weekly"))).contains("scope");
        assertThat(errorText(Map.of("scope", "recent"))).contains("sinceDays");
        assertThat(errorText(Map.of("scope", "recent", "sinceDays", 0))).contains("sinceDays");
        assertThat(errorText(Map.of("scope", "recent", "sinceDays", 366))).contains("sinceDays");
        assertThat(errorText(Map.of("cveId", "CVE-26-1"))).contains("cveId");
        assertThat(errorText(Map.of("pageSize", 0))).contains("pageSize");
        assertThat(errorText(Map.of("cursor", "garbage"))).contains("cursor");
        assertThat(errorText(Map.of("scope", "weekly"))).doesNotContain("sshole").doesNotContain("records");
    }
}
