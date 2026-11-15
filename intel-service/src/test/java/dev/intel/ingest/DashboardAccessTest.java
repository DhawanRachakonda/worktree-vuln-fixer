package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.intel.IntelServiceApplication;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.jobrunr.dashboard.JobRunrDashboardWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/** The dashboard is served only with credentials, and answers only to them. */
@ExtendWith(OutputCaptureExtension.class)
class DashboardAccessTest {

    private static final String USER = "ops";
    private static final String PASSWORD = "correct-horse-battery";

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ConfigurableApplicationContext start(Path dataDir, String... extra) {
        String[] base = {"--intel.data-dir=" + dataDir.toAbsolutePath().toString().replace('\\', '/'),
                "--jobrunr.background-job-server.enabled=false", "--intel.schedule.enabled=false",
                "--spring.main.banner-mode=off"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(IntelServiceApplication.class).web(WebApplicationType.NONE).run(args);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse<String> get(int port, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void theDashboardIsNotServedUnlessEnabled(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext context = start(dataDir)) {
            assertThat(context.getBeanNamesForType(JobRunrDashboardWebServer.class)).isEmpty();
        }
    }

    @Test
    void requestsWithoutOrWithWrongCredentialsAreRejectedAndRightOnesSeeTheJobs(@TempDir Path dataDir)
            throws Exception {
        int port = freePort();
        try (ConfigurableApplicationContext context = start(dataDir, "--jobrunr.dashboard.enabled=true",
                "--jobrunr.dashboard.username=" + USER, "--jobrunr.dashboard.password=" + PASSWORD,
                "--jobrunr.dashboard.port=" + port)) {
            context.getBean(IngestionScheduling.class).registerRecurringJob();

            HttpResponse<String> anonymous = get(port, "/api/recurring-jobs", null);
            assertThat(anonymous.statusCode()).isEqualTo(401);
            assertThat(anonymous.headers().firstValue("WWW-Authenticate")).isPresent();
            assertThat(anonymous.body()).doesNotContain(IngestionScheduling.RECURRING_JOB_ID);

            assertThat(get(port, "/api/recurring-jobs", basic(USER, "wrong-password-here")).statusCode())
                    .isEqualTo(401);
            assertThat(get(port, "/api/recurring-jobs", basic("intruder", PASSWORD)).statusCode()).isEqualTo(401);
            assertThat(get(port, "/dashboard/", null).statusCode()).isEqualTo(401);

            HttpResponse<String> authorised = get(port, "/api/recurring-jobs", basic(USER, PASSWORD));
            assertThat(authorised.statusCode()).isEqualTo(200);
            assertThat(authorised.body()).contains(IngestionScheduling.RECURRING_JOB_ID).contains("0 3 * * *")
                    .contains("\"nextRun\"");
            assertThat(get(port, "/api/jobs?state=FAILED", basic(USER, PASSWORD)).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void enablingTheDashboardWithoutCredentialsStopsStartup(@TempDir Path dataDir) throws Exception {
        int port = freePort();

        assertThatThrownBy(() -> start(dataDir, "--jobrunr.dashboard.enabled=true", "--jobrunr.dashboard.port=" + port))
                .satisfies(failure -> assertThat(chain(failure)).contains("jobrunr.dashboard.username must be set"));
        assertThat(canConnect(port)).as("nothing may be left listening after the refused start").isFalse();
    }

    @Test
    void aWeakPasswordStopsStartupWithoutEchoingIt(@TempDir Path dataDir, CapturedOutput output) throws Exception {
        int port = freePort();

        assertThatThrownBy(() -> start(dataDir, "--jobrunr.dashboard.enabled=true", "--jobrunr.dashboard.username=ops",
                "--jobrunr.dashboard.password=tooshort1", "--jobrunr.dashboard.port=" + port))
                .satisfies(failure -> {
                    assertThat(chain(failure)).contains("at least 12 characters");
                    assertThat(chain(failure)).doesNotContain("tooshort1");
                });
        assertThat(output.getAll()).doesNotContain("tooshort1");
        assertThat(canConnect(port)).isFalse();
    }

    @Test
    void thePasswordNeverAppearsInTheLogs(@TempDir Path dataDir, CapturedOutput output) throws Exception {
        int port = freePort();
        try (ConfigurableApplicationContext context = start(dataDir, "--jobrunr.dashboard.enabled=true",
                "--jobrunr.dashboard.username=" + USER, "--jobrunr.dashboard.password=" + PASSWORD,
                "--jobrunr.dashboard.port=" + port)) {
            get(port, "/api/recurring-jobs", basic(USER, PASSWORD));
            get(port, "/api/recurring-jobs", basic(USER, "wrong-password-here"));
            assertThat(context.isRunning()).isTrue();
        }

        assertThat(output.getAll()).contains("JobRunr Dashboard").doesNotContain(PASSWORD)
                .doesNotContain("wrong-password-here");
    }

    private static boolean canConnect(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append(" | ");
        }
        return text.toString();
    }
}
