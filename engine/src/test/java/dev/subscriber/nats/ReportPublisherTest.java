package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.nats.ReportPublisher.Result;
import io.nats.client.Connection;
import io.nats.client.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** The console's publish: what it refuses before it connects, and what it sends. A stand-in connection records the message. */
class ReportPublisherTest {

    private static final String TOKEN = "nats-secret-token-value";

    @TempDir
    Path temp;

    private final List<Options> optionsSeen = new ArrayList<>();
    private final Connection connection = mock(Connection.class);

    private ReportPublisher publisher(SubscriberProperties.Nats nats) {
        SubscriberProperties properties = new SubscriberProperties(temp.resolve("data").toString().replace('\\', '/'), List.of(temp.toString()),
                List.of(new Subject("securityaudit.issuefixesAgent", "Security audit", "security-fixer", null, ".appmod/security-audit/issues.csv"),
                        new Subject("coverity.issuefixesAgent", "Coverity", "appmod-harness", null, null)),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK), nats,
                new SubscriberProperties.Access(null));
        return new ReportPublisher(properties, options -> {
            optionsSeen.add(options);
            return connection;
        });
    }

    private ReportPublisher publisher() {
        return publisher(new SubscriberProperties.Nats("nats://localhost:4222", TOKEN, null, null, null));
    }

    private Path report(String name) throws IOException {
        Path file = temp.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "id,title\n1,x\n");
    }

    private byte[] published(String subject) throws Exception {
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(connection).publish(org.mockito.ArgumentMatchers.eq(subject), body.capture());
        return body.getValue();
    }

    @Test
    void aReportIsPublishedOnTheFirstConfiguredSubjectAsItsAbsolutePath() throws Exception {
        Path report = report("shop/.appmod/security-audit/issues.csv");

        Result result = publisher().publish(null, report.toString());

        assertThat(result.published()).isTrue();
        assertThat(new String(published("securityaudit.issuefixesAgent"), StandardCharsets.UTF_8)).isEqualTo(report.toAbsolutePath().normalize().toString());
        assertThat(result.message()).startsWith("Published to securityaudit.issuefixesAgent: ");
        verify(connection).flush(any(Duration.class));
        verify(connection).close();
    }

    @Test
    void anotherConfiguredSubjectCanBeNamed() throws Exception {
        Result result = publisher().publish("coverity.issuefixesAgent", report("coverity.json").toString());

        assertThat(result.published()).isTrue();
        published("coverity.issuefixesAgent");
    }

    @Test
    void aSubjectTheEngineDoesNotListenToIsRefusedWithoutConnecting() throws Exception {
        for (String subject : List.of("other.subject", "securityaudit.>", "securityaudit.*", "has space", "securityaudit.issuefixesAgent.extra")) {
            Result result = publisher().publish(subject, report("a.csv").toString());

            assertThat(result.published()).as(subject).isFalse();
            assertThat(result.message()).contains("not a subject this engine listens to").contains("securityaudit.issuefixesAgent")
                    .contains("coverity.issuefixesAgent");
        }
        assertThat(optionsSeen).isEmpty();
        verify(connection, never()).publish(anyString(), any(byte[].class));
    }

    @Test
    void aPathThatCannotBeAReportIsRefusedWithoutConnecting() throws Exception {
        Files.createDirectories(temp.resolve("folder"));
        record Case(String path, String says) {
        }
        for (Case c : List.of(new Case(null, "Give the path"), new Case("", "Give the path"), new Case("   ", "Give the path"),
                new Case(temp.resolve("missing.csv").toString(), "does not exist"), new Case(temp.resolve("folder").toString(), "not a file"),
                new Case(report("x.csv") + "\nsecond", "line breaks"), new Case(report("y.csv") + "\r", "line breaks"),
                new Case("a\0b", "null characters"))) {
            Result result = publisher().publish(null, c.path());

            assertThat(result.published()).as("%s", c.path()).isFalse();
            assertThat(result.message()).as("%s", c.path()).contains(c.says());
        }
        assertThat(optionsSeen).as("no connection was made").isEmpty();
    }

    @Test
    void aRelativePathIsMadeAbsolute() throws Exception {
        Path relative = Path.of("build.gradle.kts");
        assertThat(Files.isRegularFile(relative)).as("the test runs in the project folder").isTrue();

        Result result = publisher().publish(null, relative.toString());

        assertThat(result.published()).isTrue();
        assertThat(new String(published("securityaudit.issuefixesAgent"), StandardCharsets.UTF_8)).isEqualTo(relative.toAbsolutePath().normalize().toString());
    }

    @Test
    void anUnreachableServerIsSaidInOneSentenceWithoutTheCredentials() throws Exception {
        SubscriberProperties.Nats nats = new SubscriberProperties.Nats("nats://127.0.0.1:1", TOKEN, null, null, null);
        SubscriberProperties properties = new SubscriberProperties(temp.resolve("data").toString().replace('\\', '/'), List.of(temp.toString()),
                List.of(new Subject("securityaudit.issuefixesAgent", "Security audit", "security-fixer", null, ".appmod/security-audit/issues.csv")),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK), nats,
                new SubscriberProperties.Access(null));
        ReportPublisher failing = new ReportPublisher(properties, options -> {
            throw new IOException("Unable to connect to NATS servers: nats://127.0.0.1:1 token " + TOKEN);
        });

        Result result = failing.publish(null, report("z.csv").toString());

        assertThat(result.published()).isFalse();
        assertThat(result.message()).isEqualTo("NATS could not be reached at nats://127.0.0.1:1, so nothing was published.").doesNotContain(TOKEN);
    }

    @Test
    void theConnectionUsesTheEnginesSettingsAndOnlyForThisCall() throws Exception {
        publisher(new SubscriberProperties.Nats("nats://nats.example:4333", TOKEN, null, null, null)).publish(null, report("w.csv").toString());

        Options options = optionsSeen.getFirst();
        assertThat(options.getServers()).extracting(java.net.URI::toString).containsExactly("nats://nats.example:4333");
        assertThat(options.getConnectionName()).isEqualTo("subscriber-engine-publish");
        assertThat(options.getMaxReconnect()).as("a command does not keep trying").isZero();
        assertThat(options.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        verify(connection).close();
    }

    @Test
    void nothingTheCommandSaysContainsTheToken() throws Exception {
        Result ok = publisher().publish(null, report("v.csv").toString());
        Result refused = publisher().publish("nope", report("v.csv").toString());

        assertThat(ok.message() + refused.message()).doesNotContain(TOKEN);
    }
}
