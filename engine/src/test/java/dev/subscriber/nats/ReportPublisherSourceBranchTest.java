package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeChecker;
import dev.subscriber.nats.ReportPublisher.Result;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.worktree.SourceBranchPreview;
import io.nats.client.Connection;
import io.nats.client.impl.Headers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** The console's publish with a source branch or local changes, against a real git repository and a stand-in connection. */
class ReportPublisherSourceBranchTest {

    private static final String LOCATION = ".appmod/security-audit/issues.csv";

    @TempDir
    Path temp;

    private final Connection connection = mock(Connection.class);
    private Path repo;
    private Path report;
    private ReportPublisher publisher;

    private static String git(Path folder, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out.strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.toRealPath();
        repo = Files.createDirectories(root.resolve("shop"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "first");
        git(repo, "branch", "release/1.2");
        report = repo.resolve(LOCATION);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "file,line,title\n");
        SubscriberProperties properties = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()),
                List.of(new Subject("securityaudit.issuefixesAgent", "Security audit", "security-fixer", null, LOCATION)),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null));
        publisher = new ReportPublisher(properties, options -> connection, new IntakeChecker(properties, mock(FixSessionRepository.class)),
                new SourceBranchPreview(properties), new dev.subscriber.worktree.GitPreflight(properties));
    }

    private Headers headersSent() throws Exception {
        ArgumentCaptor<Headers> headers = ArgumentCaptor.forClass(Headers.class);
        verify(connection).publish(eq("securityaudit.issuefixesAgent"), headers.capture(), any(byte[].class));
        return headers.getValue();
    }

    @Test
    void aSourceBranchTravelsAsAHeaderAndTheBodyIsStillOnlyThePath() throws Exception {
        Result result = publisher.publish(null, report.toString(), "release/1.2", false);

        assertThat(result.published()).isTrue();
        assertThat(result.message()).contains("branch release/1.2").contains("given");
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<Headers> headers = ArgumentCaptor.forClass(Headers.class);
        verify(connection).publish(eq("securityaudit.issuefixesAgent"), headers.capture(), body.capture());
        assertThat(new String(body.getValue(), StandardCharsets.UTF_8)).isEqualTo(report.toString());
        assertThat(headers.getValue().get("source-branch")).containsExactly("release/1.2");
        assertThat(headers.getValue().containsKey("unsafe-local-changes")).isFalse();
    }

    @Test
    void localChangesTravelAsTheHeaderTrueAndTheConsoleWarnsAboutTheConfirmation() throws Exception {
        Result result = publisher.publish(null, report.toString(), null, true);

        assertThat(result.published()).isTrue();
        assertThat(result.message()).contains("uncommitted").contains("second confirmation");
        Headers headers = headersSent();
        assertThat(headers.get("unsafe-local-changes")).containsExactly("true");
        assertThat(headers.containsKey("source-branch")).isFalse();
    }

    @Test
    void withNeitherOptionTheMessageIsExactlyAPathAndTheBranchThatWillBeUsedIsSaid() throws Exception {
        Result result = publisher.publish(null, report.toString(), null, false);

        assertThat(result.published()).isTrue();
        assertThat(result.message()).contains("Source: branch main").contains("local guess");
        verify(connection).publish(eq("securityaudit.issuefixesAgent"), any(byte[].class));
        verify(connection, never()).publish(anyString(), any(Headers.class), any(byte[].class));
    }

    @Test
    void aSavedBranchIsSaidAsSaved() throws Exception {
        Files.writeString(repo.resolve(".appmod/publish.json"), "{\"sourceBranch\":\"release/1.2\"}");
        Result result = publisher.publish(null, report.toString(), null, false);
        assertThat(result.message()).contains("branch release/1.2").contains("saved");
    }

    @Test
    void aBranchThatDoesNotExistOrIsNotAValidNameOrBothOptionsPublishNothing() throws Exception {
        for (Result r : List.of(
                publisher.publish(null, report.toString(), "nope", false),
                publisher.publish(null, report.toString(), "a b", false),
                publisher.publish(null, report.toString(), "x;rm", false),
                publisher.publish(null, report.toString(), "--upload-pack=x", false),
                publisher.publish(null, report.toString(), "main", true))) {
            assertThat(r.published()).as(r.message()).isFalse();
        }
        assertThat(publisher.publish(null, report.toString(), "nope", false).message()).contains("nope").contains("does not exist");
        assertThat(publisher.publish(null, report.toString(), "a b", false).message()).contains("not usable");
        assertThat(publisher.publish(null, report.toString(), "main", true).message()).contains("Choose one");
        verify(connection, never()).publish(anyString(), any(byte[].class));
        verify(connection, never()).publish(anyString(), any(Headers.class), any(byte[].class));
    }

    @Test
    void anInvalidSavedSettingStopsThePublishWithItsReason() throws Exception {
        Files.writeString(repo.resolve(".appmod/publish.json"), "{not json");
        Result result = publisher.publish(null, report.toString(), null, false);
        assertThat(result.published()).isFalse();
        assertThat(result.message()).contains(".appmod/publish.json").contains("not valid JSON");
        verify(connection, never()).publish(anyString(), any(byte[].class));
    }
}
