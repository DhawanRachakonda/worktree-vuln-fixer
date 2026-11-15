package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeService;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Talks to a real NATS server. Opt-in: {@code ./gradlew test -Pnats-tests}. The server is either the one at {@code NATS_URL}, or, when
 * that is not set, one {@link NatsTestServer} starts (a Docker container, or {@code NATS_SERVER_EXE}) on a free loopback port and stops afterwards (the same way the other
 * live tests do); with neither the test is skipped. It uses subject names with a random part, so it can never reach a real subscriber
 * listening on the real issue subjects.
 */
class NatsLiveTest {

    @TempDir
    Path root;

    private NatsTestServer server;

    @AfterEach
    void stopServer() throws InterruptedException {
        if (server != null) {
            server.close();
        }
    }

    private String url() throws IOException {
        String configured = System.getenv("NATS_URL");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        server = NatsTestServer.start(root);
        return "nats://127.0.0.1:" + server.port();
    }

    @Test
    void messagesOnTheConfiguredSubjectsReachTheIntakeAndOthersDoNot() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("nats.tests")), "opt-in: run with -Pnats-tests and a NATS server");
        String url = url();

        String part = UUID.randomUUID().toString().substring(0, 8);
        String audit = "live-" + part + ".audit";
        String other = "live-" + part + ".coverity";
        String unconfigured = "live-" + part + ".unconfigured";
        List<Subject> subjects = List.of(
                new Subject(audit, "Security audit", "appmod-harness", null, ".appmod/security-audit/issues.csv"),
                new Subject(other, "Coverity static-analysis", "appmod-harness", null, null));
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), subjects, SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats(url, null, null, null, null), new SubscriberProperties.Access(null));
        IntakeService intake = mock(IntakeService.class);
        NatsMessageSource source = new NatsMessageSource(props, intake);

        source.start();
        try {
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(source.status()).isEqualTo(Connection.Status.CONNECTED));
            // The subscriptions are made right after the connection; give the server a moment to register them.
            Thread.sleep(500);

            String path = "C:\\work\\r&d 100% é\\.appmod\\security-audit\\issues.csv";
            try (Connection publisher = Nats.connect(url)) {
                publisher.publish(audit, path.getBytes(StandardCharsets.UTF_8));
                publisher.publish(other, "C:\\reports\\coverity.json".getBytes(StandardCharsets.UTF_8));
                publisher.publish(unconfigured, "ignored".getBytes(StandardCharsets.UTF_8));
                publisher.flush(Duration.ofSeconds(5));
            }

            verify(intake, timeout(10_000)).accept(audit, path, java.util.List.of(), java.util.List.of());
            verify(intake, timeout(10_000)).accept(other, "C:\\reports\\coverity.json", java.util.List.of(), java.util.List.of());
            Thread.sleep(500);
            verify(intake, never()).accept(org.mockito.ArgumentMatchers.eq(unconfigured), any(), any(), any());
        } finally {
            source.stop();
        }
        assertThat(source.status()).isIn(Connection.Status.CLOSED, Connection.Status.DISCONNECTED);
    }
}
