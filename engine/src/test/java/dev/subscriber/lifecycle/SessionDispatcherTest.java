package dev.subscriber.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.runner.RepositoryProbe;
import dev.subscriber.runner.RepositoryProbe.RepositoryState;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The dispatcher runs on its own thread, so these tests commit their rows (no wrapping transaction) and clean up afterwards. A
 * distinct property keeps this test's database apart from the other database tests.
 */
@JdbcTest(properties = "dispatcher.test=true")
@Import(FixSessionRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionDispatcherTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path root;

    @Autowired
    FixSessionRepository sessions;

    @Autowired
    JdbcClient jdbc;

    private SessionDispatcher dispatcher;
    private SessionFolders folders;
    private FakeLauncher launcher;

    private void start(int maxParallel, SubscriberProperties.Runner runner) {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), List.of(), runner, maxParallel,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        folders = new SessionFolders(props);
        SessionLifecycle lifecycle = new SessionLifecycle(sessions, folders, Clock.systemUTC());
        launcher = new FakeLauncher();
        RepositoryProbe probe = repository -> new RepositoryState("head-of-" + repository.getFileName(), false);
        dispatcher = new SessionDispatcher(props, sessions, lifecycle, launcher, probe);
    }

    @AfterEach
    void cleanUp() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
        jdbc.sql("DELETE FROM fix_session_event").update();
        jdbc.sql("DELETE FROM fix_session").update();
    }

    private String queue(String repositoryName) throws IOException {
        return queue(repositoryName, "security-fixer");
    }

    private String queue(String repositoryName, String agent) throws IOException {
        Path repo = Files.createDirectories(root.resolve(repositoryName));
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit",
                "report-" + id, repo.toString(), agent, "copilot", Instant.now(), null));
        return id;
    }

    /** Ends a session the way a finished agent does: an exit record and a fresh report. */
    private void endDone(String id) throws IOException {
        FixSession s = sessions.find(id).orElseThrow();
        Path folder = folders.create(id);
        Files.writeString(folder.resolve("exit.json"), "{\"exitCode\": 0, \"endedAt\": \"" + Instant.now() + "\", \"headEnd\": null}");
        Path report = Path.of(s.repositoryPath()).resolve(".appmod/result.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"status\": \"succeeded\"}");
        launcher.ends.get(id).complete(null);
    }

    private SessionState state(String id) {
        return sessions.find(id).orElseThrow().state();
    }

    private long inProgress() {
        return sessions.inProgress().size();
    }

    @Test
    void aBurstOfThreeRunsOneAtATimeInArrivalOrder() throws Exception {
        start(1, SubscriberProperties.Runner.HEADLESS);
        String a = queue("repo-a");
        String b = queue("repo-b");
        String c = queue("repo-c");
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
        assertThat(state(a)).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(state(b)).isEqualTo(SessionState.QUEUED);
        assertThat(state(c)).isEqualTo(SessionState.QUEUED);
        assertThat(inProgress()).isEqualTo(1);

        endDone(a);
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a, b));
        assertThat(state(a)).isEqualTo(SessionState.DONE);
        assertThat(state(c)).isEqualTo(SessionState.QUEUED);
        assertThat(inProgress()).isEqualTo(1);

        endDone(b);
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a, b, c));
        endDone(c);
        await().atMost(WAIT).untilAsserted(() -> assertThat(state(c)).isEqualTo(SessionState.DONE));
        assertThat(inProgress()).isZero();
        assertThat(sessions.queued()).isEmpty();
    }

    @Test
    void eachSessionIsStartedWithItsOwnRecordedAgentAndRepository() throws Exception {
        start(2, SubscriberProperties.Runner.HEADLESS);
        String a = queue("repo-a", "security-fixer");
        String b = queue("repo-b", "appmod-harness");
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactlyInAnyOrder(a, b));
        assertThat(launcher.agents).containsEntry(a, "security-fixer").containsEntry(b, "appmod-harness");
        assertThat(launcher.repositories.get(a).getFileName().toString()).isEqualTo("repo-a");
        assertThat(launcher.repositories.get(b).getFileName().toString()).isEqualTo("repo-b");
    }

    @Test
    void aSessionThatCannotBeStartedBecomesAnErrorAndTheQueueGoesOn() throws Exception {
        start(1, SubscriberProperties.Runner.HEADLESS);
        String a = queue("repo-a");
        String b = queue("repo-b");
        launcher.failing.add(a);
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(b));
        FixSession failed = sessions.find(a).orElseThrow();
        assertThat(failed.state()).isEqualTo(SessionState.ERROR);
        assertThat(failed.reason()).isEqualTo("the session could not be started: no desktop to open a window on");
        assertThat(failed.startedAt()).isNull();
        assertThat(state(b)).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(sessions.events(a)).extracting(SessionEvent::state).containsExactly(SessionState.ERROR);
    }

    @Test
    void twoSessionsForOneRepositoryNeverRunTogetherEvenWhenTheLimitAllowsIt() throws Exception {
        start(2, SubscriberProperties.Runner.HEADLESS);
        String first = queue("shared");
        String second = queue("shared");
        String other = queue("elsewhere");
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactlyInAnyOrder(first, other));
        Thread.sleep(300);
        assertThat(launcher.launched).as("the second session for the busy repository waits").doesNotContain(second);
        assertThat(state(second)).isEqualTo(SessionState.QUEUED);
        assertThat(inProgress()).isEqualTo(2);

        endDone(first);
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).contains(second));
        assertThat(state(first)).isEqualTo(SessionState.DONE);
    }

    @Test
    void theLimitOfTwoRunsTwoAtOnceAndNoMore() throws Exception {
        start(2, SubscriberProperties.Runner.HEADLESS);
        String a = queue("repo-a");
        String b = queue("repo-b");
        String c = queue("repo-c");
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactlyInAnyOrder(a, b));
        Thread.sleep(300);
        assertThat(launcher.launched).doesNotContain(c);
        assertThat(inProgress()).isEqualTo(2);

        endDone(b);
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).contains(c));
    }

    @Test
    void aSessionThatEndsWithoutARecordFreesItsSlotAndIsMarkedInterrupted() throws Exception {
        start(1, SubscriberProperties.Runner.HEADLESS);
        String a = queue("repo-a");
        String b = queue("repo-b");
        dispatcher.kick();
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));

        launcher.ends.get(a).complete(null);

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a, b));
        assertThat(state(a)).isEqualTo(SessionState.INTERRUPTED);
    }

    @Test
    void aStartedSessionHasItsRunnerProcessAndStartCommitRecordedAndAnEvent() throws Exception {
        start(1, SubscriberProperties.Runner.WINDOW);
        String a = queue("repo-a");
        dispatcher.kick();
        await().atMost(WAIT).untilAsserted(() -> assertThat(state(a)).isEqualTo(SessionState.IN_PROGRESS));

        FixSession s = sessions.find(a).orElseThrow();
        assertThat(s.runner()).isEqualTo("window");
        assertThat(s.pid()).isEqualTo(1001L);
        assertThat(s.pidStartedAt()).isNotNull();
        assertThat(s.headStart()).isEqualTo("head-of-repo-a");
        assertThat(s.dirtyAtStart()).isFalse();
        assertThat(s.startedAt()).isNotNull();
        assertThat(sessions.events(a)).extracting(SessionEvent::state).containsExactly(SessionState.IN_PROGRESS);
    }

    @Test
    void askingManyTimesNeverStartsASessionTwice() throws Exception {
        start(1, SubscriberProperties.Runner.HEADLESS);
        String a = queue("repo-a");
        for (int i = 0; i < 20; i++) {
            dispatcher.kick();
        }
        dispatcher.onQueued(new SessionQueued(a));
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
        Thread.sleep(300);
        assertThat(launcher.launched).containsExactly(a);
    }

    @Test
    void anEmptyQueueIsHarmlessAndALaterSessionIsPickedUpOnTheNextKick() throws Exception {
        start(1, SubscriberProperties.Runner.HEADLESS);
        dispatcher.kick();
        Thread.sleep(200);
        assertThat(launcher.launched).isEmpty();

        String a = queue("repo-a");
        dispatcher.onQueued(new SessionQueued(a));
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
    }

    @Test
    void theOldestQueuedSessionGoesFirstEvenWhenAYoungerOneCouldRunSooner() throws Exception {
        start(1, SubscriberProperties.Runner.HEADLESS);
        String a = queue("busy");
        String b = queue("busy");
        String c = queue("free");
        dispatcher.kick();
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));

        endDone(a);
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a, b));
        assertThat(state(c)).as("the younger session keeps waiting its turn").isEqualTo(SessionState.QUEUED);
    }

    // ---- worktrees ----

    private FakeWorktrees worktrees;

    private void startWithWorktrees(int maxParallel, SubscriberProperties.Isolation general, SubscriberProperties.Subject... subjects) {
        startWithWorktrees(false, maxParallel, general, subjects);
    }

    private void startWithWorktrees(boolean realPrompts, int maxParallel, SubscriberProperties.Isolation general,
            SubscriberProperties.Subject... subjects) {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), List.of(subjects), SubscriberProperties.Runner.HEADLESS, maxParallel,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null), general);
        folders = new SessionFolders(props);
        worktrees = new FakeWorktrees(root.resolve("worktrees"));
        SessionLifecycle lifecycle = new SessionLifecycle(sessions, folders, Clock.systemUTC(), worktrees);
        launcher = new FakeLauncher();
        RepositoryProbe probe = repository -> new RepositoryState("head-of-" + repository.getFileName(), true);
        dispatcher = new SessionDispatcher(props, sessions, lifecycle, launcher, probe, worktrees,
                realPrompts ? new dev.subscriber.session.PromptWriter(folders, props) : null);
    }

    private static SubscriberProperties.Subject audit(SubscriberProperties.Isolation isolation) {
        return new SubscriberProperties.Subject("securityaudit.issuefixesAgent", "Security audit", "security-fixer", null,
                ".appmod/security-audit/issues.csv", isolation);
    }

    /** Ends a worktree session: the agent's report is in the worktree, not in the checkout. */
    private void endDoneInWorktree(String id) throws IOException {
        FixSession s = sessions.find(id).orElseThrow();
        Path folder = folders.create(id);
        Files.writeString(folder.resolve("exit.json"), "{\"exitCode\": 0, \"endedAt\": \"" + Instant.now() + "\", \"headEnd\": null}");
        Path report = Path.of(s.workPath()).resolve(".appmod/result.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"status\": \"succeeded\"}");
        launcher.ends.get(id).complete(null);
    }

    @Test
    void aWorktreeIsCreatedAndRecordedBeforeTheLaunchAndTheAgentWorksInIt() throws Exception {
        startWithWorktrees(1, SubscriberProperties.Isolation.WORKTREE, audit(null));
        String a = queue("shop");
        worktrees.notes.put(a, List.of("report commit not used: the report has no rows; started from HEAD"));
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
        FixSession s = sessions.find(a).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(s.workName()).isEqualTo("shop-fix-" + a.substring(0, 8));
        assertThat(s.workBranch()).isEqualTo("appmod/fix-" + a.substring(0, 8));
        assertThat(s.baseCommit()).isEqualTo(FakeWorktrees.BASE);
        assertThat(launcher.repositories.get(a)).as("the agent works in the worktree").isEqualTo(Path.of(s.workPath()));
        assertThat(s.repositoryPath()).as("the repository stays the repository").endsWith("shop");
        assertThat(s.headStart()).as("the worktree starts at its base commit").isEqualTo(FakeWorktrees.BASE);
        assertThat(s.dirtyAtStart()).as("describes the person's checkout").isTrue();
        assertThat(sessions.events(a)).extracting(e -> e.detail()).containsSubsequence(
                "worktree created: " + s.workName() + " on " + s.workBranch() + " from aaaaaaa",
                "report commit not used: the report has no rows; started from HEAD", "headless started");
    }

    @Test
    void theEndIsReadFromTheWorktreeAndTheCommitsAreCounted() throws Exception {
        startWithWorktrees(1, SubscriberProperties.Isolation.WORKTREE, audit(null));
        String a = queue("shop");
        worktrees.commits.put(a.substring(0, 8), 2);
        dispatcher.kick();
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));

        endDoneInWorktree(a);

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(a)).isEqualTo(SessionState.DONE));
        FixSession done = sessions.find(a).orElseThrow();
        assertThat(done.fixCommits()).isEqualTo(2);
        assertThat(sessions.events(a).getLast().detail()).isEqualTo("2 commits on appmod/fix-" + a.substring(0, 8));
        assertThat(Path.of(done.repositoryPath()).resolve(".appmod/result.json")).as("nothing was written in the checkout")
                .doesNotExist();
    }

    @Test
    void aWorktreeThatCannotBeCreatedEndsTheSessionAsAnErrorAndTheQueueGoesOn() throws Exception {
        startWithWorktrees(1, SubscriberProperties.Isolation.WORKTREE, audit(null));
        String a = queue("repo-a");
        String b = queue("repo-b");
        worktrees.failing.add(a);
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(b));
        FixSession failed = sessions.find(a).orElseThrow();
        assertThat(failed.state()).isEqualTo(SessionState.ERROR);
        assertThat(failed.reason()).startsWith("the worktree could not be created: simulated").doesNotContain("\u001b");
        assertThat(failed.workPath()).isNull();
        assertThat(failed.fixCommits()).isNull();
        assertThat(launcher.launched).doesNotContain(a);
        assertThat(state(b)).isEqualTo(SessionState.IN_PROGRESS);
    }

    @Test
    void aLaunchThatFailsAfterTheWorktreeWasMadeStillShowsWhereTheWorktreeIs() throws Exception {
        startWithWorktrees(1, SubscriberProperties.Isolation.WORKTREE, audit(null));
        String a = queue("repo-a");
        launcher.failing.add(a);
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(a)).isEqualTo(SessionState.ERROR));
        FixSession s = sessions.find(a).orElseThrow();
        assertThat(s.reason()).startsWith("the session could not be started");
        assertThat(s.workPath()).isNotNull();
        assertThat(s.workBranch()).isEqualTo("appmod/fix-" + a.substring(0, 8));
    }

    @Test
    void aSubjectWithIsolationNoneRunsInTheCheckoutAndCreatesNothing() throws Exception {
        startWithWorktrees(1, SubscriberProperties.Isolation.WORKTREE, audit(SubscriberProperties.Isolation.NONE));
        String a = queue("shop");
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
        FixSession s = sessions.find(a).orElseThrow();
        assertThat(worktrees.created).isEmpty();
        assertThat(s.workPath()).isNull();
        assertThat(launcher.repositories.get(a)).isEqualTo(Path.of(s.repositoryPath()));
        assertThat(s.headStart()).isEqualTo("head-of-shop");
        endDone(a);
        await().atMost(WAIT).untilAsserted(() -> assertThat(state(a)).isEqualTo(SessionState.DONE));
        assertThat(sessions.find(a).orElseThrow().fixCommits()).isNull();
        assertThat(sessions.events(a)).extracting(e -> e.detail()).noneMatch(d -> d.contains("worktree") || d.contains("commit"));
    }

    @Test
    void twoSessionsForOneRepositoryStillNeverRunTogetherEachWithItsOwnWorktree() throws Exception {
        startWithWorktrees(2, SubscriberProperties.Isolation.WORKTREE, audit(null));
        String a = queue("shop");
        String b = queue("shop");
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
        assertThat(state(b)).isEqualTo(SessionState.QUEUED);
        assertThat(worktrees.created).as("no worktree is made for a session that waits").containsExactly(a);

        endDoneInWorktree(a);
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a, b));
        assertThat(sessions.find(a).orElseThrow().workName()).isNotEqualTo(sessions.find(b).orElseThrow().workName());
    }

    @Test
    void theRewrittenPromptNamesTheWorktreeAndTheCheckout() throws Exception {
        startWithWorktrees(true, 1, SubscriberProperties.Isolation.WORKTREE, audit(null));
        String a = queue("shop");
        Path snapshot = Files.createDirectories(folders.create(a).resolve("report")).resolve("issues.csv");
        Files.writeString(snapshot, "file,line,commit\n");
        sessions.setSnapshot(a, snapshot.toString());
        dispatcher.kick();

        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(a));
        FixSession s = sessions.find(a).orElseThrow();
        String prompt = Files.readString(folders.promptFile(a));
        assertThat(prompt).contains("working in the repository at `" + s.workPath() + "`");
        assertThat(prompt).contains("branch `" + s.workBranch() + "`");
        assertThat(prompt).contains("checkout is at `" + s.repositoryPath() + "`");
    }
}
