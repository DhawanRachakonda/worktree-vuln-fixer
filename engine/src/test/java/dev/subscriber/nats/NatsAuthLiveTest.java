package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeService;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Talks to NATS servers that require credentials. Opt-in: {@code ./gradlew test -Pnats-tests} with the {@code nats-server} program found at the
 * environment variable {@code NATS_SERVER_EXE} (or on the PATH). Each test starts its own server on a free loopback port and stops it, so
 * nothing else is touched. Credentials in the form of a {@code .creds} file need an operator-mode server and are not covered here.
 */
@ExtendWith(OutputCaptureExtension.class)
class NatsAuthLiveTest {

    private static final String TOKEN = "server-token-8f3a1c9d2b7e4a60";
    private static final String USER = "engine";
    private static final String PASSWORD = "engine-pass-5d7c2e91ab34";

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

    private int startServer(String... authArguments) throws IOException {
        Assumptions.assumeTrue("true".equals(System.getProperty("nats.tests")), "opt-in: run with -Pnats-tests");
        String exe = System.getenv("NATS_SERVER_EXE");
        Assumptions.assumeTrue(exe != null && Files.isExecutable(Path.of(exe)), "set NATS_SERVER_EXE to the nats-server program");
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        List<String> command = new java.util.ArrayList<>(List.of(exe, "-a", "127.0.0.1", "-p", String.valueOf(port)));
        command.addAll(List.of(authArguments));
        server = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("server.log").toFile()).start();
        await().atMost(Duration.ofSeconds(10)).ignoreExceptions().until(() -> {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return s.isConnected();
            }
        });
        return port;
    }

    private NatsMessageSource sourceFor(int port, String subject, SubscriberProperties.Nats nats, IntakeService intake) {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()),
                List.of(new Subject(subject, "Security audit", "appmod-harness", null, ".appmod/security-audit/issues.csv")),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                nats, new SubscriberProperties.Access(null));
        source = new NatsMessageSource(props, intake);
        return source;
    }

    private static String url(int port) {
        return "nats://127.0.0.1:" + port;
    }

    @Test
    void aTokenServerAcceptsTheEngineWithTheRightTokenAndDeliversItsMessages() throws Exception {
        int port = startServer("--auth", TOKEN);
        String subject = "auth-" + UUID.randomUUID().toString().substring(0, 8) + ".audit";
        IntakeService intake = mock(IntakeService.class);

        sourceFor(port, subject, new SubscriberProperties.Nats(url(port), TOKEN, null, null, null), intake).start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(source.status()).isEqualTo(Connection.Status.CONNECTED));
        Thread.sleep(500);
        try (Connection publisher = Nats.connect(new Options.Builder().server(url(port)).token(TOKEN.toCharArray()).build())) {
            publisher.publish(subject, "C:\\work\\shop\\.appmod\\security-audit\\issues.csv".getBytes(StandardCharsets.UTF_8));
            publisher.flush(Duration.ofSeconds(5));
        }

        verify(intake, timeout(10_000)).accept(subject, "C:\\work\\shop\\.appmod\\security-audit\\issues.csv");
    }

    @Test
    void aUserAndPasswordServerAcceptsTheEngineWithTheRightPair() throws Exception {
        int port = startServer("--user", USER, "--pass", PASSWORD);
        String subject = "auth-" + UUID.randomUUID().toString().substring(0, 8) + ".audit";
        IntakeService intake = mock(IntakeService.class);

        sourceFor(port, subject, new SubscriberProperties.Nats(url(port), null, USER, PASSWORD, null), intake).start();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(source.status()).isEqualTo(Connection.Status.CONNECTED));
        Thread.sleep(500);
        try (Connection publisher = Nats.connect(new Options.Builder().server(url(port)).userInfo(USER.toCharArray(), PASSWORD.toCharArray()).build())) {
            publisher.publish(subject, "C:\\x\\issues.csv".getBytes(StandardCharsets.UTF_8));
            publisher.flush(Duration.ofSeconds(5));
        }

        verify(intake, timeout(10_000)).accept(subject, "C:\\x\\issues.csv");
    }

    @Test
    void aWrongTokenNeverConnectsAndNeitherTheWrongNorTheRightTokenIsLogged(CapturedOutput output) throws Exception {
        int port = startServer("--auth", TOKEN);
        String wrong = "wrong-token-1b2c3d4e5f60718293";
        IntakeService intake = mock(IntakeService.class);

        sourceFor(port, "auth-wrong.audit", new SubscriberProperties.Nats(url(port), wrong, null, null, null), intake).start();
        Thread.sleep(6000);

        assertThat(source.status()).as("a refused login is never CONNECTED").isNotEqualTo(Connection.Status.CONNECTED);
        verify(intake, never()).accept(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        assertThat(output.getAll()).doesNotContain(wrong).doesNotContain(TOKEN);
    }

    @Test
    void noCredentialsAgainstAServerThatNeedsThemNeverConnects(CapturedOutput output) throws Exception {
        int port = startServer("--user", USER, "--pass", PASSWORD);

        sourceFor(port, "auth-none.audit", new SubscriberProperties.Nats(url(port), null, null, null, null), mock(IntakeService.class)).start();
        Thread.sleep(6000);

        assertThat(source.status()).isNotEqualTo(Connection.Status.CONNECTED);
        assertThat(output.getAll()).doesNotContain(PASSWORD);
    }
}
