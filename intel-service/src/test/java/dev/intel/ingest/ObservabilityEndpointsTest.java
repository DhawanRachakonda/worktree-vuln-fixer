package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.feed.FetchFailedException;
import dev.intel.feed.FetchResult;
import dev.intel.store.IngestionTrigger;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/** The health and metrics endpoints of the running service, after real ingestion runs against a scripted feed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:observability;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false",
        "intel.schedule.enabled=false"
})
@Import(ObservabilityEndpointsTest.Scripted.class)
class ObservabilityEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        ScriptedFeedSource scriptedFeedSource() {
            return new ScriptedFeedSource();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ScriptedFeedSource feed;

    @Autowired
    IngestionService ingestion;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        feed.clear();
        jdbc.sql("DELETE FROM vulnerability").update();
        jdbc.sql("DELETE FROM ingestion_run").update();
        jdbc.sql("DELETE FROM feed_source_state").update();
    }

    private static byte[] fixture() {
        try (InputStream in = ObservabilityEndpointsTest.class.getResourceAsStream("/fixtures/zero-wire-feed.xml")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(String json) throws Exception {
        return JSON.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @SuppressWarnings("unchecked")
    private double measurement(String name, String tag) throws Exception {
        HttpResponse<String> response = get("/actuator/metrics/" + name + "?tag=" + tag);
        assertThat(response.statusCode()).as("%s %s", name, tag).isEqualTo(200);
        List<Map<String, Object>> measurements = (List<Map<String, Object>>) map(response.body()).get("measurements");
        return ((Number) measurements.get(0).get("value")).doubleValue();
    }

    private void ingestFixture() {
        feed.returns(new FetchResult.Fetched(fixture(), null, null,
                URI.create("https://radar.cloudflare.com/security/zero-wire/rss")));
        ingestion.run(IngestionTrigger.MANUAL);
    }

    // ------------------------------------------------------------------ health

    @Test
    void theIngestionComponentIsUpAfterASuccessfulRunAndCarriesItsDetails() throws Exception {
        ingestFixture();

        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> health = map(response.body());
        assertThat(health.get("status")).isEqualTo("UP");
        Map<String, Object> ingestionComponent = child(child(health, "components"), "ingestion");
        assertThat(ingestionComponent.get("status")).isEqualTo("UP");
        Map<String, Object> details = child(ingestionComponent, "details");
        assertThat(details).containsEntry("freshness", "OK").containsEntry("stale", false);
        assertThat(child(details, "lastRun")).containsEntry("outcome", "SUCCESS").containsEntry("itemsNew", 8);
    }

    @Test
    void theSingleComponentCanBeReadOnItsOwn() throws Exception {
        ingestFixture();

        HttpResponse<String> response = get("/actuator/health/ingestion");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(map(response.body()).get("status")).isEqualTo("UP");
    }

    @Test
    void withNothingIngestedTheServiceReportsDownWithTheReason() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(503);
        Map<String, Object> health = map(response.body());
        assertThat(health.get("status")).isEqualTo("DOWN");
        Map<String, Object> details = child(child(child(health, "components"), "ingestion"), "details");
        assertThat(details).containsEntry("freshness", "NEVER_INGESTED").containsEntry("lastRun", "none");
    }

    @Test
    void theLivenessAndReadinessProbesDoNotDependOnIngestion() throws Exception {
        assertThat(get("/actuator/health/liveness").statusCode()).as("nothing ingested yet").isEqualTo(200);
        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness").body()).doesNotContain("ingestion").doesNotContain("details");
    }

    // ------------------------------------------------------------------ metrics

    @Test
    void theCountersAreVisibleThroughTheMetricsEndpointAndMoveWithRuns() throws Exception {
        // The counters are cumulative and shared by every test in this context, so assert what each run adds.
        double newBefore = measurement("intel.ingestion.items", "result:new");
        double successBefore = measurement("intel.ingestion.runs", "outcome:SUCCESS");
        double unchangedBefore = measurement("intel.ingestion.items", "result:unchanged");
        double noChangeBefore = measurement("intel.ingestion.runs", "outcome:NO_CHANGE");
        double failedBefore = measurement("intel.ingestion.runs", "outcome:FAILED");

        ingestFixture();
        assertThat(measurement("intel.ingestion.items", "result:new")).isEqualTo(newBefore + 8);
        assertThat(measurement("intel.ingestion.runs", "outcome:SUCCESS")).isEqualTo(successBefore + 1);

        feed.returns(new FetchResult.Fetched(fixture(), null, null,
                URI.create("https://radar.cloudflare.com/security/zero-wire/rss")));
        ingestion.run(IngestionTrigger.SCHEDULED);
        assertThat(measurement("intel.ingestion.items", "result:unchanged")).isEqualTo(unchangedBefore + 8);
        assertThat(measurement("intel.ingestion.runs", "outcome:NO_CHANGE")).isEqualTo(noChangeBefore + 1);

        feed.fails(new FetchFailedException("HTTP 500 from 'radar.cloudflare.com'"));
        ingestion.run(IngestionTrigger.SCHEDULED);
        assertThat(measurement("intel.ingestion.runs", "outcome:FAILED")).isEqualTo(failedBefore + 1);
    }

    @Test
    void jobRunrsOwnJobAndServerMetricsAreAlsoPublished() throws Exception {
        HttpResponse<String> response = get("/actuator/metrics");

        assertThat(response.statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) map(response.body()).get("names");
        assertThat(names).anyMatch(name -> name.startsWith("jobrunr."));
        assertThat(names).contains("intel.ingestion.runs", "intel.ingestion.items");
    }
}
