package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeService;
import dev.subscriber.nats.ReportPublisher.Result;
import io.nats.client.Connection;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The console's publish against a real NATS server that needs a token, with the engine's own subscriber on the other side. Opt-in:
 * {@code ./gradlew test -Pnats-tests} with the {@code nats-server} program at the environment variable {@code NATS_SERVER_EXE}. The server
 * is started on a free loopback port for each test and stopped after it.
 */
class ReportPublisherLiveTest {

    private static final String TOKEN = "server-token-8f3a1c9d2b7e4a60";

    @TempDir
    Path root;

    private Process server;
    private NatsMessageSource source;

    @AfterEach
    void stop() throws InterruptedException {
        if (source != null) {
            source.stop();
        }
        if (server != null) {
            server.destroyForcibly().waitFor();
        }
    }

    private int startServer() throws IOException {
        Assumptions.assumeTrue("true".equals(System.getProperty("nats.tests")), "opt-in: run with -Pnats-tests");
        String exe = System.getenv("NATS_SERVER_EXE");
        Assumptions.assumeTrue(exe != null && Files.isExecutable(Path.of(exe)), "set NATS_SERVER_EXE to the nats-server program");
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        List<String> command = new ArrayList<>(List.of(exe, "-a", "127.0.0.1", "-p", String.valueOf(port), "--auth", TOKEN));
        server = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("server.log").toFile()).start();
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() -> {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return s.isConnected();
            }
        });
        return port;
    }

    private SubscriberProperties properties(int port, String subject, String token) {
        return new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()),
                List.of(new Subject(subject, "Security audit", "security-fixer", null, ".appmod/security-audit/issues.csv")),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://127.0.0.1:" + port, token, null, null, null), new SubscriberProperties.Access(null));
    }

    @Test
    void aPublishedReportReachesTheEnginesSubscriberThroughARealServerThatNeedsAToken() throws Exception {
        int port = startServer();
        String subject = "publish-" + UUID.randomUUID().toString().substring(0, 8) + ".audit";
        IntakeService intake = mock(IntakeService.class);
        source = new NatsMessageSource(properties(port, subject, TOKEN), intake);
        source.start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(source.status()).isEqualTo(Connection.Status.CONNECTED));
        Thread.sleep(500);
        Path report = Files.writeString(Files.createDirectories(root.resolve("shop/.appmod/security-audit")).resolve("issues.csv"), "id\n");

        Result result = new ReportPublisher(properties(port, subject, TOKEN)).publish(null, report.toString());

        assertThat(result.published()).as(result.message()).isTrue();
        verify(intake, timeout(10_000)).accept(subject, report.toAbsolutePath().normalize().toString());
    }

    @Test
    void aWrongTokenIsRefusedInOneSentenceThatShowsNeitherToken() throws Exception {
        int port = startServer();
        String subject = "publish-" + UUID.randomUUID().toString().substring(0, 8) + ".audit";
        String wrong = "wrong-token-1b2c3d4e5f60718293";
        Path report = Files.writeString(root.resolve("issues.csv"), "id\n");

        Result result = new ReportPublisher(properties(port, subject, wrong)).publish(null, report.toString());

        assertThat(result.published()).isFalse();
        assertThat(result.message()).contains("NATS could not be reached").doesNotContain(wrong).doesNotContain(TOKEN);
    }
}
