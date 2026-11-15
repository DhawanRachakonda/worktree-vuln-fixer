package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** With an access token the operational endpoints need it too, except the two orchestrator probes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:actuator-token;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false",
        "intel.schedule.enabled=false",
        "intel.access.token=actuator-token-5d2c-long-random"
})
class ActuatorAccessTokenTest {

    private static final String TOKEN = "actuator-token-5d2c-long-random";

    @LocalServerPort
    int port;

    private int status(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }

    @Test
    void healthDetailsAndMetricsNeedTheToken() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/ingestion", "/actuator/metrics",
                "/actuator/metrics/intel.ingestion.runs", "/actuator")) {
            assertThat(status(path, null)).as("%s without a token", path).isEqualTo(401);
            assertThat(status(path, "Bearer wrong-token")).as("%s with a wrong token", path).isEqualTo(401);
        }
        assertThat(status("/actuator/metrics", "Bearer " + TOKEN)).isEqualTo(200);
        assertThat(status("/actuator/health/ingestion", "Bearer " + TOKEN)).as("DOWN: nothing ingested yet")
                .isEqualTo(503);
    }

    @Test
    void theTwoOrchestratorProbesStayOpen() throws Exception {
        assertThat(status("/actuator/health/liveness", null)).isEqualTo(200);
        assertThat(status("/actuator/health/readiness", null)).isEqualTo(200);
    }

    @Test
    void onlyTheExactProbePathsAreOpen() throws Exception {
        for (String path : List.of("/actuator/health/liveness/extra", "/actuator/health/livenessx",
                "/actuator/health/liveness/../metrics", "/actuator//metrics", "/actuator/health/readiness/..")) {
            int code = status(path, null);
            assertThat(code).as(path).isIn(400, 401, 404);
            assertThat(code).as("%s must never return data", path).isNotEqualTo(200);
        }
    }
}
