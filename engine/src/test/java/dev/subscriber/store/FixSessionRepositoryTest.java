package dev.subscriber.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.store.FixSessionRepository.NewSession;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@JdbcTest
@Import(FixSessionRepository.class)
class FixSessionRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00.123456Z");

    @Autowired
    FixSessionRepository repo;

    @Autowired
    JdbcClient jdbc;

    private String queue(String report, String repository, Instant at) {
        String id = UUID.randomUUID().toString();
        repo.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", report,
                repository, "appmod-harness", "copilot", at, null));
        return id;
    }

    @Test
    void anInsertedSessionIsFoundWithEveryFieldAndKeepsItsInstantToTheMicrosecond() {
        String id = queue("C:/r/.appmod/security-audit/issues.csv", "C:/r", T0);
        FixSession s = repo.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.QUEUED);
        assertThat(s.subject()).isEqualTo("securityaudit.issuefixesAgent");
        assertThat(s.report()).isEqualTo("C:/r/.appmod/security-audit/issues.csv");
        assertThat(s.repositoryPath()).isEqualTo("C:/r");
        assertThat(s.agent()).isEqualTo("appmod-harness");
        assertThat(s.engine()).isEqualTo("copilot");
        assertThat(s.receivedAt()).isEqualTo(T0);
        assertThat(s.startedAt()).isNull();
        assertThat(s.pid()).isNull();
        assertThat(s.exitCode()).isNull();
        assertThat(repo.find("no-such-id")).isEmpty();
    }

    @Test
    void listIsNewestFirstFilterableByStateAndPaged() {
        String a = queue("a", "r1", T0);
        String b = queue("b", "r2", T0);
        String c = queue("c", "r3", T0);
        repo.markFinal(b, SessionState.ERROR, T0, 1, null, null, "boom");
        assertThat(repo.list(null, 10, 0)).extracting(FixSession::id).containsExactly(c, b, a);
        assertThat(repo.list(SessionState.ERROR, 10, 0)).extracting(FixSession::id).containsExactly(b);
        assertThat(repo.list(null, 2, 0)).extracting(FixSession::id).containsExactly(c, b);
        assertThat(repo.list(null, 2, 2)).extracting(FixSession::id).containsExactly(a);
        assertThat(repo.count(null)).isEqualTo(3);
        assertThat(repo.count(SessionState.QUEUED)).isEqualTo(2);
    }

    @Test
    void queuedSessionsComeInArrivalOrderEvenWithTheSameTimestamp() {
        List<String> ids = List.of(queue("1", "r", T0), queue("2", "r", T0), queue("3", "r", T0), queue("4", "r", T0));
        assertThat(repo.queued()).extracting(FixSession::id).containsExactlyElementsOf(ids);
    }

    @Test
    void startingMovesAQueuedSessionToInProgressOnceOnly() {
        String id = queue("a", "r", T0);
        Instant start = T0.plusSeconds(5);
        assertThat(repo.markStarted(id, "window", start, 4242L, start.minusSeconds(1), "abc123", true)).isTrue();
        FixSession s = repo.find(id).orElseThrow();
        assertThat(s.state()).isEqualTo(SessionState.IN_PROGRESS);
        assertThat(s.runner()).isEqualTo("window");
        assertThat(s.startedAt()).isEqualTo(start);
        assertThat(s.pid()).isEqualTo(4242L);
        assertThat(s.pidStartedAt()).isEqualTo(start.minusSeconds(1));
        assertThat(s.headStart()).isEqualTo("abc123");
        assertThat(s.dirtyAtStart()).isTrue();
        assertThat(repo.markStarted(id, "window", start, 1L, start, "x", false)).isFalse();
        assertThat(repo.find(id).orElseThrow().pid()).isEqualTo(4242L);
        assertThat(repo.inProgress()).extracting(FixSession::id).containsExactly(id);
    }

    @Test
    void aFinalStateIsRecordedOnceAndNeverChangedAgain() {
        String id = queue("a", "r", T0);
        repo.markStarted(id, "window", T0, 1L, T0, "h1", false);
        Instant end = T0.plusSeconds(60);
        assertThat(repo.markFinal(id, SessionState.DONE, end, 0, "h2", "succeeded", null)).isTrue();
        FixSession done = repo.find(id).orElseThrow();
        assertThat(done.state()).isEqualTo(SessionState.DONE);
        assertThat(done.endedAt()).isEqualTo(end);
        assertThat(done.exitCode()).isZero();
        assertThat(done.headEnd()).isEqualTo("h2");
        assertThat(done.outcome()).isEqualTo("succeeded");

        assertThat(repo.markFinal(id, SessionState.ERROR, end.plusSeconds(1), 9, "h3", "failed", "late")).isFalse();
        assertThat(repo.find(id).orElseThrow()).isEqualTo(done);
        assertThat(repo.markStarted(id, "window", end, 2L, end, "h", false)).isFalse();
        assertThat(repo.find(id).orElseThrow().state()).isEqualTo(SessionState.DONE);
    }

    @Test
    void aQueuedSessionCanBeFinishedDirectlyAndANonFinalStateIsRefused() {
        String id = queue("a", "r", T0);
        assertThatThrownBy(() -> repo.markFinal(id, SessionState.IN_PROGRESS, T0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.markFinal(id, SessionState.ERROR, T0, null, null, null, "window could not be opened")).isTrue();
        assertThat(repo.find(id).orElseThrow().reason()).isEqualTo("window could not be opened");
    }

    @Test
    void eventsComeBackInTheOrderTheyHappened() {
        String id = queue("a", "r", T0);
        repo.appendEvent(id, T0, SessionState.QUEUED, "received");
        repo.appendEvent(id, T0.plusSeconds(1), SessionState.IN_PROGRESS, "window opened");
        repo.appendEvent(id, T0.plusSeconds(2), SessionState.DONE, null);
        assertThat(repo.events(id)).extracting(SessionEvent::state)
                .containsExactly(SessionState.QUEUED, SessionState.IN_PROGRESS, SessionState.DONE);
        assertThat(repo.events(id).get(1).detail()).isEqualTo("window opened");
        assertThat(repo.events(id).get(2).detail()).isNull();
        assertThat(repo.events("other")).isEmpty();
    }

    @Test
    void activeSessionLookupsCoverQueuedAndRunningButNotFinished() {
        String id = queue("C:/r/report.csv", "C:/r", T0);
        assertThat(repo.hasActiveForReport("C:/r/report.csv")).isTrue();
        assertThat(repo.hasActiveForRepository("C:/r")).isTrue();
        assertThat(repo.hasActiveForRepository("C:/other")).isFalse();
        repo.markStarted(id, "window", T0, 1L, T0, "h", false);
        assertThat(repo.hasActiveForReport("C:/r/report.csv")).isTrue();
        repo.markFinal(id, SessionState.DONE, T0, 0, "h", "succeeded", null);
        assertThat(repo.hasActiveForReport("C:/r/report.csv")).isFalse();
        assertThat(repo.hasActiveForRepository("C:/r")).isFalse();
    }

    @Test
    void theDatabaseRefusesAnUnknownStateAndSaysWhichRuleItBroke() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO fix_session (id, state, subject, source, report_path, agent, engine, received_at)
                VALUES ('x', 'BOGUS', 's', 's', 'r', 'a', 'e', CURRENT_TIMESTAMP)""").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("FK_FIX_SESSION_STATE");
    }

    @Test
    void anEventWithAnUnknownStateIsRefusedToo() {
        String id = queue("a", "r", T0);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO fix_session_event (session_id, occurred_at, state) VALUES (:id, CURRENT_TIMESTAMP, 'BOGUS')""")
                .param("id", id).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("FK_FIX_SESSION_EVENT_STATE");
    }

    @Test
    void theStatesKnownToTheDatabaseAreExactlyTheStatesOfTheEnum() {
        List<String> inDatabase = jdbc.sql("SELECT name FROM fix_session_state ORDER BY name").query(String.class).list();
        assertThat(inDatabase).containsExactlyInAnyOrderElementsOf(
                java.util.Arrays.stream(SessionState.values()).map(Enum::name).toList());
    }

    @Test
    void anEventNeedsAnExistingSession() {
        assertThatThrownBy(() -> repo.appendEvent("missing", T0, SessionState.QUEUED, "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void veryLongPayloadsAndReasonsAreShortenedNotRefused() {
        String id = UUID.randomUUID().toString();
        repo.insert(new NewSession(id, SessionState.REJECTED, "securityaudit.issuefixesAgent", "Security audit", "x".repeat(5000),
                null, "appmod-harness", "copilot", T0, "r".repeat(5000)));
        FixSession s = repo.find(id).orElseThrow();
        assertThat(s.report()).hasSize(1024).endsWith("…");
        assertThat(s.reason()).hasSize(2000).endsWith("…");
        assertThat(s.state()).isEqualTo(SessionState.REJECTED);
    }

    @Test
    void aStartedSessionKeepsItsWorktreeAndTheCountIsWrittenOnlyWithTheFinalState() {
        String id = queue("r", "C:/r", T0);
        Worktree worktree = new Worktree("r-fix-0a1b2c3d", "C:/r-fix-0a1b2c3d", "appmod/fix-0a1b2c3d", "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432");
        assertThat(repo.setWorktree(id, worktree)).isTrue();
        assertThat(repo.markStarted(id, "window", T0, 1L, T0, "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432", true)).isTrue();

        FixSession running = repo.find(id).orElseThrow();
        assertThat(running.workName()).isEqualTo("r-fix-0a1b2c3d");
        assertThat(running.workPath()).isEqualTo("C:/r-fix-0a1b2c3d");
        assertThat(running.workBranch()).isEqualTo("appmod/fix-0a1b2c3d");
        assertThat(running.baseCommit()).isEqualTo("9f8e7d6c5b4a39281706f5e4d3c2b1a098765432");
        assertThat(running.fixCommits()).as("not counted before the end").isNull();
        assertThat(running.hasWorktree()).isTrue();
        assertThat(running.workingPath()).isEqualTo("C:/r-fix-0a1b2c3d");

        assertThat(repo.markFinal(id, SessionState.DONE, T0, 0, "head", "succeeded", null, 2)).isTrue();
        FixSession done = repo.find(id).orElseThrow();
        assertThat(done.fixCommits()).isEqualTo(2);
        assertThat(done.workPath()).as("the worktree stays recorded").isEqualTo("C:/r-fix-0a1b2c3d");

        assertThat(repo.markFinal(id, SessionState.ERROR, T0, 1, null, null, "later", 9)).as("a final state never changes").isFalse();
        assertThat(repo.find(id).orElseThrow().fixCommits()).isEqualTo(2);
    }

    @Test
    void aSessionWithoutAWorktreeHasEmptyFieldsAndWorksInTheRepository() {
        String id = queue("r", "C:/r", T0);
        repo.markStarted(id, "window", T0, 1L, T0, "h", false);
        repo.markFinal(id, SessionState.DONE, T0, 0, "h", "succeeded", null);

        FixSession s = repo.find(id).orElseThrow();
        assertThat(s.workName()).isNull();
        assertThat(s.workPath()).isNull();
        assertThat(s.workBranch()).isNull();
        assertThat(s.baseCommit()).isNull();
        assertThat(s.fixCommits()).isNull();
        assertThat(s.hasWorktree()).isFalse();
        assertThat(s.workingPath()).isEqualTo("C:/r");
    }

    @Test
    void aWorktreeCannotBeAttachedToASessionThatIsNotQueued() {
        String id = queue("r", "C:/r", T0);
        repo.markFinal(id, SessionState.ERROR, T0, null, null, null, "boom");

        Worktree worktree = new Worktree("n", "C:/n", "appmod/fix-n", "abc");
        assertThat(repo.setWorktree(id, worktree)).isFalse();
        assertThat(repo.find(id).orElseThrow().workPath()).isNull();
    }
}
