package dev.intel.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.IntelServiceApplication;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The MCP endpoint and the operations dashboard are two protected surfaces with two different secrets. Neither secret
 * opens the other surface, and the settings of one never weaken the other.
 */
class SurfaceIndependenceTest {

    private static final String MCP_TOKEN = "mcp-token-9f31-long-random";
    private static final String DASHBOARD_USER = "ops";
    private static final String DASHBOARD_PASSWORD = "dashboard-pass-7c2d";
    private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ConfigurableApplicationContext start(Path dataDir, int appPort, String... extra) {
        String[] base = {"--server.port=" + appPort, "--intel.data-dir=" + dataDir.toAbsolutePath().toString()
                .replace('\\', '/'), "--jobrunr.background-job-server.enabled=false", "--intel.schedule.enabled=false",
                "--spring.main.banner-mode=off"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(IntelServiceApplication.class).web(WebApplicationType.SERVLET).run(args);
    }

    private static int mcp(int port, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INITIALIZE));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }

    private static int dashboard(int port, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + "/api/recurring-jobs")).GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void neitherSecretOpensTheOtherSurface(@TempDir Path dataDir) throws Exception {
        int appPort = freePort();
        int dashboardPort = freePort();
        try (ConfigurableApplicationContext context = start(dataDir, appPort, "--intel.access.token=" + MCP_TOKEN,
                "--jobrunr.dashboard.enabled=true", "--jobrunr.dashboard.username=" + DASHBOARD_USER,
                "--jobrunr.dashboard.password=" + DASHBOARD_PASSWORD, "--jobrunr.dashboard.port=" + dashboardPort)) {
            // each surface accepts its own secret
            assertThat(mcp(appPort, "Bearer " + MCP_TOKEN)).as("MCP with its token").isEqualTo(200);
            assertThat(dashboard(dashboardPort, basic(DASHBOARD_USER, DASHBOARD_PASSWORD)))
                    .as("dashboard with its credentials").isEqualTo(200);

            // the dashboard's credentials do not open MCP, in either form
            assertThat(mcp(appPort, basic(DASHBOARD_USER, DASHBOARD_PASSWORD))).isEqualTo(401);
            assertThat(mcp(appPort, "Bearer " + DASHBOARD_PASSWORD)).isEqualTo(401);

            // the MCP token does not open the dashboard, as a bearer token or as a password
            assertThat(dashboard(dashboardPort, "Bearer " + MCP_TOKEN)).isEqualTo(401);
            assertThat(dashboard(dashboardPort, basic(DASHBOARD_USER, MCP_TOKEN))).isEqualTo(401);
            assertThat(dashboard(dashboardPort, basic("mcp", MCP_TOKEN))).isEqualTo(401);

            // and neither surface answers anonymously
            assertThat(mcp(appPort, null)).isEqualTo(401);
            assertThat(dashboard(dashboardPort, null)).isEqualTo(401);
        }
    }

    @Test
    void anOpenLoopbackOnlyMcpEndpointDoesNotWeakenTheDashboard(@TempDir Path dataDir) throws Exception {
        int appPort = freePort();
        int dashboardPort = freePort();
        try (ConfigurableApplicationContext context = start(dataDir, appPort, "--jobrunr.dashboard.enabled=true",
                "--jobrunr.dashboard.username=" + DASHBOARD_USER, "--jobrunr.dashboard.password=" + DASHBOARD_PASSWORD,
                "--jobrunr.dashboard.port=" + dashboardPort)) {
            assertThat(mcp(appPort, null)).as("no token configured: MCP is open on loopback").isEqualTo(200);

            assertThat(dashboard(dashboardPort, null)).as("the dashboard still demands credentials").isEqualTo(401);
            assertThat(dashboard(dashboardPort, basic(DASHBOARD_USER, "wrong-password-here"))).isEqualTo(401);
            assertThat(dashboard(dashboardPort, basic(DASHBOARD_USER, DASHBOARD_PASSWORD))).isEqualTo(200);
        }
    }

    @Test
    void aTokenOnMcpDoesNotSwitchTheDashboardOn(@TempDir Path dataDir) throws Exception {
        int appPort = freePort();
        int dashboardPort = freePort();
        try (ConfigurableApplicationContext context = start(dataDir, appPort, "--intel.access.token=" + MCP_TOKEN,
                "--jobrunr.dashboard.port=" + dashboardPort)) {
            assertThat(mcp(appPort, "Bearer " + MCP_TOKEN)).isEqualTo(200);
            assertThat(listening(dashboardPort)).as("the dashboard stays off unless enabled").isFalse();
        }
    }
}
