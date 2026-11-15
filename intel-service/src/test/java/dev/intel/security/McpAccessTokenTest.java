package dev.intel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.intel.feed.FeedDocumentReader;
import dev.intel.feed.FeedEntry;
import dev.intel.feed.FeedItemNormalizer;
import dev.intel.feed.Fixtures;
import dev.intel.feed.SectionSplitter;
import dev.intel.ingest.IngestionService;
import dev.intel.query.VulnerabilityQueryService;
import dev.intel.store.VulnerabilityRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** With a token configured the MCP endpoint answers only to it, and rejected requests never reach any tool code. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp-token;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false",
        "intel.schedule.enabled=false",
        "intel.access.token=k7Qx-a-long-random-token-9f31"
})
class McpAccessTokenTest {

    private static final String TOKEN = "k7Qx-a-long-random-token-9f31";
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
    private static final String CALL = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"listVulnerabilities\",\"arguments\":{\"scope\":\"all\"}}}";

    @LocalServerPort
    int port;

    @MockitoSpyBean
    VulnerabilityQueryService queries;

    @Autowired
    VulnerabilityRepository vulnerabilities;

    @Autowired
    JdbcClient jdbc;

    private McpSyncClient client;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("DELETE FROM vulnerability").update();
        FeedItemNormalizer normalizer = new FeedItemNormalizer(new SectionSplitter());
        for (FeedEntry entry : new FeedDocumentReader().read(Fixtures.feedBytes())) {
            vulnerabilities.upsert(normalizer.normalize(IngestionService.SOURCE_NAME, entry), Instant.now());
        }
        org.mockito.Mockito.clearInvocations(queries);
    }

    @AfterEach
    void close() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    private HttpResponse<String> post(String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private McpSyncClient clientWith(String authorization) {
        HttpClientStreamableHttpTransport.Builder transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint("/mcp");
        if (authorization != null) {
            transport.httpRequestCustomizer((builder, method, endpoint, body, context) ->
                    builder.header("Authorization", authorization));
        }
        client = McpClient.sync(transport.build()).requestTimeout(Duration.ofSeconds(20)).build();
        return client;
    }

    // ------------------------------------------------------------------ the right token

    @Test
    void aClientWithTheTokenSeesTheToolAndGetsRecords() {
        McpSyncClient authorised = clientWith("Bearer " + TOKEN);
        authorised.initialize();

        assertThat(authorised.listTools().tools()).extracting(McpSchema.Tool::name)
                .containsExactly("listVulnerabilities");
        McpSchema.CallToolResult result = authorised.callTool(new McpSchema.CallToolRequest("listVulnerabilities",
                Map.of("scope", "all")));
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.content().toString()).contains("sshole@2026-11-05");
    }

    @Test
    void theSchemeNameIsCaseInsensitive() throws Exception {
        assertThat(post("/mcp", INITIALIZE, "bearer " + TOKEN).statusCode()).isEqualTo(200);
        assertThat(post("/mcp", INITIALIZE, "BEARER " + TOKEN).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ everything else

    @Test
    void aMissingTokenIsRejectedBeforeAnyToolCodeRuns() throws Exception {
        HttpResponse<String> response = post("/mcp", CALL, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertThat(response.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        assertThat(response.body()).doesNotContain("records").doesNotContain("sshole");
        verifyNoInteractions(queries);
    }

    @Test
    void wrongOrMalformedCredentialsAreRejectedAndReturnNoRecords() throws Exception {
        List<String> bad = List.of(
                "Bearer wrong-token",
                "Bearer " + TOKEN + "x",
                "Bearer " + TOKEN.substring(0, TOKEN.length() - 1),
                "Bearer " + TOKEN.toUpperCase(),
                "Bearer",
                "Bearer ",
                TOKEN,
                "Basic " + Base64.getEncoder().encodeToString(("ops:" + TOKEN).getBytes()),
                "Token " + TOKEN);

        for (String authorization : bad) {
            HttpResponse<String> response = post("/mcp", CALL, authorization);
            assertThat(response.statusCode()).as("Authorization: %s", authorization).isEqualTo(401);
            assertThat(response.body()).as(authorization).doesNotContain("records").doesNotContain("sshole");
        }
        verifyNoInteractions(queries);
    }

    @Test
    void theTokenInTheQueryStringIsNotAccepted() throws Exception {
        assertThat(post("/mcp?token=" + TOKEN, CALL, null).statusCode()).isEqualTo(401);
        assertThat(post("/mcp?access_token=" + TOKEN, CALL, null).statusCode()).isEqualTo(401);
        verifyNoInteractions(queries);
    }

    @Test
    void everyMethodOnTheEndpointIsProtected() throws Exception {
        for (String method : List.of("GET", "DELETE", "PUT", "OPTIONS")) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            try (HttpClient http = HttpClient.newHttpClient()) {
                assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                        .as(method).isEqualTo(401);
            }
        }
    }

    @Test
    void anMcpClientWithoutTheTokenCannotEvenInitialize() {
        McpSyncClient anonymous = clientWith(null);

        assertThatThrownBy(anonymous::initialize).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(queries);
    }

    @Test
    void theTokenNeverAppearsInTheLogs(CapturedOutput output) throws Exception {
        post("/mcp", CALL, "Bearer " + TOKEN);
        post("/mcp", CALL, "Bearer wrong-token");
        post("/mcp", CALL, null);

        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain("wrong-token");
    }

    @Test
    void theComparisonTreatsDifferentLengthsAndEqualPrefixesAlike() {
        BearerTokenFilter filter = new BearerTokenFilter(TOKEN);

        assertThat(filter.authorized("Bearer " + TOKEN)).isTrue();
        assertThat(filter.authorized("Bearer " + TOKEN + " ")).as("surrounding spaces are stripped").isTrue();
        assertThat(filter.authorized("Bearer " + TOKEN.substring(0, 5))).isFalse();
        assertThat(filter.authorized("Bearer " + TOKEN + TOKEN)).isFalse();
        assertThat(filter.authorized(null)).isFalse();
    }
}
