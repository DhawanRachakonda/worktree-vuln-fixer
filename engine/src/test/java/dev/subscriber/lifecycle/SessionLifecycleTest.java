package dev.subscriber.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import(FixSessionRepository.class)
class SessionLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @TempDir
    Path root;

    @Autowired
    FixSessionRepository sessions;

    private SessionFolders folders;
    private SessionLifecycle lifecycle;
    private Path repo;

    @BeforeEach
    void setUp() throws IOException {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), List.of(), SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        folders = new SessionFolders(props);
        lifecycle = new SessionLifecycle(sessions, folders, Clock.fixed(NOW, ZoneOffset.UTC));
        repo = Files.createDirectories(root.resolve("shop"));
    }

    private String queued() {
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "r",
                repo.toString(), "security-fixer", "copilot", NOW.minusSeconds(100), null));
        return id;
    }

    private void exitRecord(String id, int code, String head) throws IOException {
        Path folder = folders.create(id);
        Files.writeString(folder.resolve("exit.json"), "{\"exitCode\": " + code + ", \"endedAt\": \"2026-11-10T12:30:00Z\", \"headEnd\": "
                + (head == null ? "null" : "\"" + head + "\"") + "}");
    }

    private void report(String status, Instant modified) throws IOException {
        Path file = repo.resolve(".appmod/result.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"status\": \"" + status + "\"}");
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    @Test
    void startingRecordsTheProcessAndTheCommitAndWritesTheEvent() {
        String id = queued();
        assertThat(lifecycle.started(id, "window", 4242L, NOW.minusSeconds(1), "abc123", true)).isTrue();
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(s.runner()).isEqualTo("window");
        assertThat(s.startedAt()).isEqualTo(NOW);
        assertThat(s.pid()).isEqualTo(4242L);
        assertThat(s.headStart()).isEqualTo("abc123");
        assertThat(s.dirtyAtStart()).isTrue();
        assertThat(sessions.events(id)).extracting(SessionEvent::state).containsExactly(SessionState.IN_PROGRESS);
        assertThat(sessions.events(id).get(0).detail()).isEqualTo("window started");
        assertThat(lifecycle.started(id, "window", 1L, NOW, "x", false)).as("only a queued session can start").isFalse();
        assertThat(sessions.events(id)).hasSize(1);
    }

    @Test
    void aSessionThatEndedCleanlyWithAFreshSucceededReportIsDoneWithEverythingRecorded() throws IOException {
        String id = queued();
        lifecycle.started(id, "window", 1L, NOW, "start-sha", false);
        exitRecord(id, 0, "end-sha");
        report("succeeded", NOW.plusSeconds(600));

        assertThat(lifecycle.finish(id)).contains(SessionState.DONE);
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.DONE);
        assertThat(s.exitCode()).isZero();
        assertThat(s.headStart()).isEqualTo("start-sha");
        assertThat(s.headEnd()).isEqualTo("end-sha");
        assertThat(s.endedAt()).as("the end time of the exit record").isEqualTo(Instant.parse("2026-11-10T12:30:00Z"));
        assertThat(s.outcome()).isEqualTo("succeeded");
        assertThat(s.reason()).isNull();
        assertThat(sessions.events(id)).extracting(SessionEvent::state).containsExactly(SessionState.IN_PROGRESS, SessionState.DONE);
    }

    @Test
    void aSessionWhoseReportIsOlderThanItsStartIsAnErrorBecauseTheReportIsFromAnEarlierSession() throws IOException {
        String id = queued();
        lifecycle.started(id, "window", 1L, NOW, "h", false);
        exitRecord(id, 0, "h");
        report("succeeded", NOW.minusSeconds(3600));

        assertThat(lifecycle.finish(id)).contains(SessionState.ERROR);
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.reason()).isEqualTo("the agent ended without a report");
        assertThat(s.outcome()).isNull();
    }

    @Test
    void aSessionWithoutAnExitRecordIsInterruptedAndHasNoExitCode() {
        String id = queued();
        lifecycle.started(id, "window", 1L, NOW, "h", false);

        assertThat(lifecycle.finish(id)).contains(SessionState.INTERRUPTED);
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.exitCode()).isNull();
        assertThat(s.headEnd()).isNull();
        assertThat(s.endedAt()).isEqualTo(NOW);
        assertThat(s.reason()).contains("without leaving an exit record");
    }

    @Test
    void aNonZeroExitIsAnErrorWithTheCodeInTheReason() throws IOException {
        String id = queued();
        lifecycle.started(id, "window", 1L, NOW, "h", false);
        exitRecord(id, 7, null);

        assertThat(lifecycle.finish(id)).contains(SessionState.ERROR);
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.exitCode()).isEqualTo(7);
        assertThat(s.headEnd()).isNull();
        assertThat(s.reason()).contains("code 7");
    }

    @Test
    void aFinalStateIsNeverChangedAgainAndNoSecondEventIsWritten() throws IOException {
        String id = queued();
        lifecycle.started(id, "window", 1L, NOW, "h", false);
        exitRecord(id, 0, "h");
        report("partially_succeeded", NOW.plusSeconds(1));
        assertThat(lifecycle.finish(id)).contains(SessionState.PARTIAL);
        FixSession before = sessions.find(id).orElseThrow();
        int events = sessions.events(id).size();

        report("failed", NOW.plusSeconds(500));
        assertThat(lifecycle.finish(id)).as("finishing again returns the existing state").contains(SessionState.PARTIAL);
        assertThat(lifecycle.error(id, "late failure")).isFalse();
        assertThat(lifecycle.interrupted(id, "late interruption")).isFalse();
        assertThat(lifecycle.started(id, "window", 2L, NOW, "x", false)).isFalse();

        assertThat(sessions.find(id).orElseThrow()).isEqualTo(before);
        assertThat(sessions.events(id)).hasSize(events);
    }

    @Test
    void aLaunchFailureMovesAQueuedSessionDirectlyToErrorWithItsReason() {
        String id = queued();
        assertThat(lifecycle.error(id, "the window could not be opened: no desktop")).isTrue();
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.ERROR);
        assertThat(s.reason()).isEqualTo("the window could not be opened: no desktop");
        assertThat(s.startedAt()).isNull();
        assertThat(sessions.events(id)).extracting(SessionEvent::state).containsExactly(SessionState.ERROR);
    }

    @Test
    void anUnknownSessionIsNotAnError() {
        assertThat(lifecycle.finish(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void aHostileStatusInTheAgentsReportCannotBreakTheDatabaseWrite() throws IOException {
        String id = queued();
        lifecycle.started(id, "window", 1L, NOW, "h", false);
        exitRecord(id, 0, "h");
        report("x".repeat(5000), NOW.plusSeconds(1));

        assertThat(lifecycle.finish(id)).contains(SessionState.ERROR);
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.outcome()).hasSize(30);
        assertThat(s.reason()).contains("unknown status");
    }

    // ---- worktrees ----

    private FakeWorktrees fake;
    private SessionLifecycle withWorktrees;

    private String queuedInWorktree() throws IOException {
        fake = new FakeWorktrees(root.resolve("worktrees"));
        withWorktrees = new SessionLifecycle(sessions, folders, Clock.fixed(NOW, ZoneOffset.UTC), fake);
        String id = queued();
        Path work = Files.createDirectories(root.resolve("shop-fix-" + id.substring(0, 8)));
        withWorktrees.worktreeCreated(id, new dev.subscriber.store.Worktree("shop-fix-" + id.substring(0, 8), work.toString(),
                "appmod/fix-" + id.substring(0, 8), "b".repeat(40)), List.of("report commit not used: the report has no rows; started from HEAD"));
        return id;
    }

    private void reportIn(Path folder, String status, Instant modified) throws IOException {
        Path file = folder.resolve(".appmod/result.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"status\": \"" + status + "\"}");
        Files.setLastModifiedTime(file, FileTime.from(modified));
    }

    @Test
    void theWorktreeIsRecordedWithOneEventAndOneForEachNote() throws IOException {
        String id = queuedInWorktree();

        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.state()).as("recording the worktree does not start the session").isEqualTo(SessionState.QUEUED);
        assertThat(s.workName()).isEqualTo("shop-fix-" + id.substring(0, 8));
        assertThat(s.workBranch()).isEqualTo("appmod/fix-" + id.substring(0, 8));
        assertThat(s.baseCommit()).isEqualTo("b".repeat(40));
        assertThat(sessions.events(id)).extracting(SessionEvent::detail).containsExactly(
                "worktree created: " + s.workName() + " on " + s.workBranch() + " from bbbbbbb",
                "report commit not used: the report has no rows; started from HEAD");
    }

    @Test
    void aWorktreeCanOnlyBeRecordedForAQueuedSession() throws IOException {
        String id = queuedInWorktree();
        withWorktrees.started(id, "window", 1L, NOW, "h", false);
        int events = sessions.events(id).size();

        boolean again = withWorktrees.worktreeCreated(id, new dev.subscriber.store.Worktree("other", "C:/other", "appmod/fix-other", "c"),
                List.of());

        assertThat(again).isFalse();
        assertThat(sessions.find(id).orElseThrow().workName()).isNotEqualTo("other");
        assertThat(sessions.events(id)).hasSize(events);
    }

    @Test
    void theReportIsReadFromTheWorktreeAndNotFromTheCheckout() throws IOException {
        String id = queuedInWorktree();
        withWorktrees.started(id, "window", 1L, NOW.minusSeconds(60), "h", false);
        exitRecord(id, 0, null);
        reportIn(repo, "succeeded", NOW.plusSeconds(10));   // left in the checkout: must not count

        assertThat(withWorktrees.finish(id)).contains(SessionState.ERROR);
        assertThat(sessions.find(id).orElseThrow().reason()).contains("without a report");
    }

    @Test
    void aReportInTheWorktreeCountsAndTheCommitsAreCountedWithTheirEvent() throws IOException {
        String id = queuedInWorktree();
        withWorktrees.started(id, "window", 1L, NOW.minusSeconds(60), "h", false);
        exitRecord(id, 0, "end");
        reportIn(Path.of(sessions.find(id).orElseThrow().workPath()), "succeeded", NOW.plusSeconds(10));
        fake.commits.put(id.substring(0, 8), 2);

        assertThat(withWorktrees.finish(id)).contains(SessionState.DONE);

        FixSession done = sessions.find(id).orElseThrow();
        assertThat(done.outcome()).isEqualTo("succeeded");
        assertThat(done.fixCommits()).isEqualTo(2);
        assertThat(sessions.events(id).getLast().detail()).isEqualTo("2 commits on appmod/fix-" + id.substring(0, 8));
        assertThat(sessions.events(id).getLast().state()).isEqualTo(SessionState.DONE);
    }

    @Test
    void oneCommitIsSaidInTheSingular() throws IOException {
        String id = queuedInWorktree();
        withWorktrees.started(id, "window", 1L, NOW.minusSeconds(60), "h", false);
        exitRecord(id, 0, null);
        reportIn(Path.of(sessions.find(id).orElseThrow().workPath()), "partially_succeeded", NOW.plusSeconds(10));
        fake.commits.put(id.substring(0, 8), 1);

        withWorktrees.finish(id);

        assertThat(sessions.find(id).orElseThrow().state()).isEqualTo(SessionState.PARTIAL);
        assertThat(sessions.events(id).getLast().detail()).isEqualTo("1 commit on appmod/fix-" + id.substring(0, 8));
    }

    @Test
    void theCountIsRecordedForErrorAndInterruptedSessionsToo() throws IOException {
        String failed = queuedInWorktree();
        withWorktrees.started(failed, "window", 1L, NOW.minusSeconds(60), "h", false);
        fake.commits.put(failed.substring(0, 8), 3);
        withWorktrees.error(failed, "the session could not be followed");
        assertThat(sessions.find(failed).orElseThrow().fixCommits()).isEqualTo(3);
        assertThat(sessions.events(failed).getLast().detail()).isEqualTo("3 commits on appmod/fix-" + failed.substring(0, 8));

        String interrupted = queuedInWorktree();
        withWorktrees.started(interrupted, "window", 1L, NOW.minusSeconds(60), "h", false);
        fake.commits.put(interrupted.substring(0, 8), 1);
        withWorktrees.interrupted(interrupted, "the window was gone after a restart and left no exit record");
        assertThat(sessions.find(interrupted).orElseThrow().fixCommits()).isEqualTo(1);
        assertThat(sessions.events(interrupted).getLast().state()).isEqualTo(SessionState.INTERRUPTED);

        String nonZero = queuedInWorktree();
        withWorktrees.started(nonZero, "window", 1L, NOW.minusSeconds(60), "h", false);
        exitRecord(nonZero, 2, null);
        fake.commits.put(nonZero.substring(0, 8), 0);
        withWorktrees.finish(nonZero);
        assertThat(sessions.find(nonZero).orElseThrow().state()).isEqualTo(SessionState.ERROR);
        assertThat(sessions.find(nonZero).orElseThrow().fixCommits()).isZero();
        assertThat(sessions.events(nonZero).getLast().detail()).isEqualTo("0 commits on appmod/fix-" + nonZero.substring(0, 8));
    }

    @Test
    void aCountThatCannotBeReadIsSaidSoAndDoesNotChangeTheState() throws IOException {
        String id = queuedInWorktree();
        withWorktrees.started(id, "window", 1L, NOW.minusSeconds(60), "h", false);
        exitRecord(id, 0, null);
        reportIn(Path.of(sessions.find(id).orElseThrow().workPath()), "succeeded", NOW.plusSeconds(10));
        // no count is known to the fake for this session

        assertThat(withWorktrees.finish(id)).contains(SessionState.DONE);

        assertThat(sessions.find(id).orElseThrow().fixCommits()).isNull();
        assertThat(sessions.events(id).getLast().detail()).contains("could not be counted");
    }

    @Test
    void aSessionWithoutAWorktreeHasNoCountAndNoCommitEvent() throws IOException {
        fake = new FakeWorktrees(root.resolve("worktrees"));
        withWorktrees = new SessionLifecycle(sessions, folders, Clock.fixed(NOW, ZoneOffset.UTC), fake);
        String id = queued();
        withWorktrees.started(id, "window", 1L, NOW.minusSeconds(60), "h", false);
        exitRecord(id, 0, null);
        report("succeeded", NOW.plusSeconds(10));

        assertThat(withWorktrees.finish(id)).contains(SessionState.DONE);

        assertThat(sessions.find(id).orElseThrow().fixCommits()).isNull();
        assertThat(sessions.events(id)).extracting(SessionEvent::detail).noneMatch(d -> d.contains("commit"));
    }
}
