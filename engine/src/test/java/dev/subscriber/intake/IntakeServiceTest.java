package dev.subscriber.intake;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.session.PromptWriter;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import(FixSessionRepository.class)
@ExtendWith(OutputCaptureExtension.class)
class IntakeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final String LOCATION = ".appmod/security-audit/issues.csv";

    @TempDir
    Path root;

    @Autowired
    FixSessionRepository sessions;

    private Subject audit;
    private Subject coverity;
    private IntakeService service;
    private SessionFolders folders;
    private final List<Object> published = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        audit = new Subject("securityaudit.issuefixesAgent", "Security audit", "security-fixer", null, LOCATION);
        coverity = new Subject("coverity.issuefixesAgent", "Coverity static-analysis", "appmod-harness", null, null);
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), List.of(audit, coverity), SubscriberProperties.Runner.AUTO, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        folders = new SessionFolders(props);
        service = new IntakeService(props, new IntakeChecker(props, sessions), sessions, folders,
                new PromptWriter(folders, props), published::add, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Path report(String repoName) throws IOException {
        Path repo = root.resolve(repoName);
        Files.createDirectories(repo.resolve(".git"));
        Path report = repo.resolve(LOCATION);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "file,line,title\r\n");
        return report;
    }

    @Test
    void anAcceptedMessageBecomesAQueuedSessionWithItsFirstEventAndNothingStarted() throws IOException {
        Path report = report("shop");
        String id = service.accept("securityaudit.issuefixesAgent", report.toString()).orElseThrow();

        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.QUEUED);
        assertThat(s.subject()).isEqualTo("securityaudit.issuefixesAgent");
        assertThat(s.source()).isEqualTo("Security audit");
        assertThat(s.agent()).as("the subject's configured agent").isEqualTo("security-fixer");
        assertThat(s.engine()).isEqualTo("copilot");
        assertThat(s.report()).isEqualTo(report.toRealPath().toString());
        assertThat(s.repositoryPath()).isEqualTo(root.resolve("shop").toRealPath().toString());
        assertThat(s.receivedAt()).isEqualTo(NOW);
        assertThat(s.startedAt()).as("nothing was launched").isNull();
        assertThat(s.pid()).isNull();
        assertThat(sessions.inProgress()).isEmpty();
        assertThat(sessions.events(id)).extracting(SessionEvent::state).containsExactly(SessionState.QUEUED);
        assertThat(sessions.queued()).extracting(FixSession::id).containsExactly(id);
    }

    @Test
    void anAcceptedSessionHasASnapshotOfTheReportAndAPromptThatNamesIt() throws IOException {
        Path report = report("shop");
        String id = service.accept("securityaudit.issuefixesAgent", report.toString()).orElseThrow();
        FixSession s = sessions.find(id).orElseThrow();
        Path snapshot = Path.of(s.snapshotPath());
        assertThat(snapshot).isEqualTo(folders.folder(id).resolve("report").resolve("issues.csv"));
        assertThat(Files.readAllBytes(snapshot)).isEqualTo(Files.readAllBytes(report));
        assertThat(Files.readString(folders.promptFile(id))).contains("`" + snapshot + "`").contains("`" + report.toRealPath() + "`")
                .contains("security audit report");
        Files.writeString(report, "overwritten by a newer audit");
        assertThat(snapshot).as("the snapshot is unaffected").hasContent("file,line,title\r\n");
    }

    @Test
    void aReportThatCannotBeCopiedBecomesARejectedSessionAndLeavesNoFolder() throws IOException {
        Path report = report("shop");
        Files.write(report, new byte[21 * 1024 * 1024]);
        String id = service.accept("securityaudit.issuefixesAgent", report.toString()).orElseThrow();
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.REJECTED);
        assertThat(s.reason()).contains("could not be copied").contains("larger than 20 MiB");
        assertThat(folders.folder(id)).doesNotExist();
        assertThat(sessions.queued()).isEmpty();
    }

    @Test
    void onlyAnAcceptedMessageAnnouncesAQueuedSession() throws IOException {
        String id = service.accept("securityaudit.issuefixesAgent", report("shop").toString()).orElseThrow();
        assertThat(published).containsExactly(new dev.subscriber.lifecycle.SessionQueued(id));

        service.accept("securityaudit.issuefixesAgent", "relative.csv");
        service.accept("something.else", "x");
        assertThat(published).as("a rejected or ignored message announces nothing").hasSize(1);
    }

    @Test
    void aMessageOnAnotherSubjectIsIgnoredAndRecordsNothing(CapturedOutput output) throws IOException {
        Path report = report("shop");
        assertThat(service.accept("something.else", report.toString())).isEmpty();
        assertThat(service.accept(null, report.toString())).isEmpty();
        assertThat(sessions.count(null)).isZero();
        assertThat(output.getAll()).contains("Ignoring a message on the unsupported subject something.else");
    }

    @Test
    void eachRefusedMessageBecomesARejectedSessionWithItsReasonAndNothingElse() throws IOException {
        Path report = report("shop");
        List<String> payloads = List.of("", "relative.csv", report + "\nmore", report + "\0", root.resolve("nothing.csv").toString(),
                root.resolve("shop").toString());
        for (String payload : payloads) {
            String id = service.accept("securityaudit.issuefixesAgent", payload).orElseThrow();
            FixSession s = sessions.find(id).orElseThrow();
            assertThat(s.state()).as("payload %s", IntakeService.printable(payload)).isEqualTo(SessionState.REJECTED);
            assertThat(s.reason()).isNotBlank();
            assertThat(s.report()).as("the payload is kept as received").isEqualTo(payload);
            assertThat(s.repositoryPath()).isNull();
            assertThat(s.startedAt()).isNull();
            assertThat(sessions.events(id)).extracting(SessionEvent::state).containsExactly(SessionState.REJECTED);
            assertThat(sessions.events(id).get(0).detail()).isEqualTo(s.reason());
        }
        assertThat(sessions.queued()).isEmpty();
        assertThat(sessions.inProgress()).isEmpty();
    }

    @Test
    void aReportOutsideTheRootsAndAReportNotAtTheAuditLocationAreRejectedWithTheirReasons(@TempDir Path outside) throws IOException {
        Path foreign = outside.resolve("repo/" + LOCATION);
        Files.createDirectories(foreign.getParent());
        Files.createDirectories(outside.resolve("repo/.git"));
        Files.writeString(foreign, "x");
        FixSession a = sessions.find(service.accept("securityaudit.issuefixesAgent", foreign.toString()).orElseThrow()).orElseThrow();
        assertThat(a.state()).isEqualTo(SessionState.REJECTED);
        assertThat(a.reason()).contains("outside the allowed roots");

        Path misplaced = root.resolve("shop/elsewhere/issues.csv");
        Files.createDirectories(misplaced.getParent());
        Files.createDirectories(root.resolve("shop/.git"));
        Files.writeString(misplaced, "x");
        FixSession b = sessions.find(service.accept("securityaudit.issuefixesAgent", misplaced.toString()).orElseThrow()).orElseThrow();
        assertThat(b.state()).isEqualTo(SessionState.REJECTED);
        assertThat(b.reason()).contains("repository could not be determined");
    }

    @Test
    void aSecondMessageForTheSameReportIsRejectedAsADuplicateAndTheFirstStaysQueued() throws IOException {
        Path report = report("shop");
        String first = service.accept("securityaudit.issuefixesAgent", report.toString()).orElseThrow();
        String second = service.accept("securityaudit.issuefixesAgent", report.toString()).orElseThrow();
        assertThat(sessions.find(first).orElseThrow().state()).isEqualTo(SessionState.QUEUED);
        FixSession dup = sessions.find(second).orElseThrow();
        assertThat(dup.state()).isEqualTo(SessionState.REJECTED);
        assertThat(dup.reason()).contains("duplicate");
        assertThat(sessions.queued()).extracting(FixSession::id).containsExactly(first);
    }

    @Test
    void aSubjectWithoutAConfiguredRepositoryRejectsWithThatReason() throws IOException {
        Path report = root.resolve("coverity.json");
        Files.writeString(report, "{}");
        FixSession s = sessions.find(service.accept("coverity.issuefixesAgent", report.toString()).orElseThrow()).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.REJECTED);
        assertThat(s.reason()).contains("no repository is configured");
    }

    @Test
    void textFromAMessageIsNeverLoggedRawSoItCannotForgeOrHideLogLines(CapturedOutput output) {
        // The payload itself is never logged (only the stored reason); the one message-derived text that is logged is the subject.
        String hostileSubject = "x\n2026-11-10 12:00:00 INFO forged line: session accepted\u001b[2K";
        assertThat(service.accept(hostileSubject, "C:/x")).isEmpty();
        service.accept("securityaudit.issuefixesAgent", "x\n2026-11-10 12:00:00 INFO forged payload line\u001b[2K");
        String log = output.getAll();
        assertThat(log).doesNotContain("\n2026-11-10 12:00:00 INFO forged").doesNotContain("\u001b");
        assertThat(log).contains("\\u000a2026-11-10 12:00:00 INFO forged line");
        assertThat(log).as("the payload does not appear in the log at all").doesNotContain("forged payload line");
    }

    @Test
    void messagesArePrintedShortenedInLogs() {
        assertThat(IntakeService.printable("a".repeat(1000))).hasSize(303).endsWith("...");
        assertThat(IntakeService.printable(null)).isEqualTo("(none)");
        assertThat(Optional.of(IntakeService.printable("plain text"))).contains("plain text");
    }

    @Test
    void theSessionRecordsTheToolThatWillRunIt() throws IOException {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), List.of(audit, coverity), SubscriberProperties.Runner.AUTO, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.NONE, SubscriberProperties.AgentTool.CLAUDE, new SubscriberProperties.Claude("claude"));
        IntakeService claude = new IntakeService(props, new IntakeChecker(props, sessions), sessions, folders,
                new PromptWriter(folders, props), published::add, Clock.fixed(NOW, ZoneOffset.UTC));
        Path report = report("claude-repo");

        String id = claude.accept("securityaudit.issuefixesAgent", report.toString()).orElseThrow();

        assertThat(sessions.find(id).orElseThrow().engine()).isEqualTo("claude");
    }
}
