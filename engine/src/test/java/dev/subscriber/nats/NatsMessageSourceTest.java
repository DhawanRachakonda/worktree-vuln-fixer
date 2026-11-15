package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeService;
import io.nats.client.Connection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class NatsMessageSourceTest {

    @TempDir
    Path root;

    static SubscriberProperties properties(Path root, SubscriberProperties.Nats nats) {
        List<Subject> subjects = List.of(
                new Subject("securityaudit.issuefixesAgent", "Security audit", "appmod-harness", null, ".appmod/security-audit/issues.csv"),
                new Subject("coverity.issuefixesAgent", "Coverity static-analysis", "appmod-harness", null, null));
        return new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()), subjects,
                SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK), nats,
                new SubscriberProperties.Access(null));
    }

    private static SubscriberProperties.Nats plain(String url) {
        return new SubscriberProperties.Nats(url, null, null, null, null);
    }

    @Test
    void aMessageIsDecodedAsUtf8AndHandedToTheIntakeWithItsSubject() {
        IntakeService intake = mock(IntakeService.class);
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://localhost:4222")), intake);
        source.handle("securityaudit.issuefixesAgent", "C:\\work\\r&d é\\.appmod\\security-audit\\issues.csv".getBytes(StandardCharsets.UTF_8));
        verify(intake).accept("securityaudit.issuefixesAgent", "C:\\work\\r&d é\\.appmod\\security-audit\\issues.csv");
    }

    @Test
    void anEmptyOrMissingPayloadIsHandedOverAsEmptyText() {
        IntakeService intake = mock(IntakeService.class);
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://localhost:4222")), intake);
        source.handle("coverity.issuefixesAgent", new byte[0]);
        source.handle("coverity.issuefixesAgent", null);
        verify(intake, org.mockito.Mockito.times(2)).accept("coverity.issuefixesAgent", "");
    }

    @Test
    void aHugePayloadIsCutBeforeItIsDecoded() {
        IntakeService intake = mock(IntakeService.class);
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://localhost:4222")), intake);
        source.handle("coverity.issuefixesAgent", "x".repeat(1_000_000).getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(intake).accept(eq("coverity.issuefixesAgent"), payload.capture());
        assertThat(payload.getValue()).hasSize(NatsMessageSource.MAX_PAYLOAD_BYTES);
    }

    @Test
    void aFailureToRecordAMessageIsLoggedAndDoesNotEscapeIntoTheConnection(CapturedOutput output) {
        IntakeService intake = mock(IntakeService.class);
        doThrow(new IllegalStateException("database is locked")).when(intake).accept(any(), any());
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://localhost:4222")), intake);

        source.handle("coverity.issuefixesAgent", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(output.getAll()).contains("could not be recorded and is lost").contains("database is locked");
    }

    @Test
    void aSubjectWithControlCharactersCannotForgeALogLine(CapturedOutput output) {
        IntakeService intake = mock(IntakeService.class);
        doThrow(new IllegalStateException("boom")).when(intake).accept(any(), any());
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://localhost:4222")), intake);

        source.handle("a\n2026-11-10 INFO forged\u001b[2K", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(output.getAll()).doesNotContain("\n2026-11-10 INFO forged").doesNotContain("\u001b");
    }

    @Test
    void theStatusIsClosedUntilTheSourceHasBeenStarted() {
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://localhost:4222")), mock(IntakeService.class));
        assertThat(source.status()).isEqualTo(Connection.Status.CLOSED);
        source.stop();
    }

    @Test
    void startingReturnsAtOnceWhenTheServerIsNotThereAndStoppingEndsTheConnectorThread() throws Exception {
        NatsMessageSource source = new NatsMessageSource(properties(root, plain("nats://127.0.0.1:1")), mock(IntakeService.class));
        long before = connectors();
        long begun = System.nanoTime();
        source.start();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - begun)).as("start does not wait for the server")
                .isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(source.status()).as("started but not connected").isEqualTo(Connection.Status.DISCONNECTED);
        assertThat(connectors()).as("a connector thread was added").isEqualTo(before + 1);

        source.stop();

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> assertThat(connectors())
                .as("the connector thread ended, so no reconnect loop is left running").isEqualTo(before));
    }

    private static long connectors() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals(NatsMessageSource.CONNECTOR_THREAD) && thread.isAlive()).count();
    }

    @Test
    void credentialsNeverAppearInTheLogsWhateverTheServerDoes(CapturedOutput output) throws Exception {
        Path credentials = Files.writeString(root.resolve("user.creds"), "-----BEGIN NATS USER JWT-----\nSECRET-JWT-CONTENT\n------END NATS USER JWT------\n");
        List<SubscriberProperties.Nats> variants = List.of(
                new SubscriberProperties.Nats("nats://127.0.0.1:1", "SECRET-TOKEN-VALUE", null, null, null),
                new SubscriberProperties.Nats("nats://127.0.0.1:1", null, "alice-the-user", "SECRET-PASSWORD-VALUE", null),
                new SubscriberProperties.Nats("nats://127.0.0.1:1", null, null, null, credentials.toString()));
        for (SubscriberProperties.Nats nats : variants) {
            NatsMessageSource source = new NatsMessageSource(properties(root, nats), mock(IntakeService.class));
            // Nothing listens on port 1: the connection keeps trying, which is exactly when errors get logged.
            source.start();
            Thread.sleep(1500);
            assertThat(source.status()).isNotEqualTo(Connection.Status.CONNECTED);
            source.stop();
        }
        verify(mock(IntakeService.class), never()).accept(any(), any());
        assertThat(output.getAll()).doesNotContain("SECRET-TOKEN-VALUE").doesNotContain("SECRET-PASSWORD-VALUE")
                .doesNotContain("SECRET-JWT-CONTENT").doesNotContain("alice-the-user");
    }
}
