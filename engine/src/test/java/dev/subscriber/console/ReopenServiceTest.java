package dev.subscriber.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import(FixSessionRepository.class)
class ReopenServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-11T09:00:00Z");

    @Autowired
    FixSessionRepository sessions;

    @TempDir
    Path repository;

    @TempDir
    Path otherRepository;

    private final List<String> opened = new ArrayList<>();
    private final List<String> engines = new ArrayList<>();
    private IOException windowFailure;
    private ReopenService service;

    @BeforeEach
    void service() {
        service = new ReopenService(sessions, (id, repo, engine) -> {
            if (windowFailure != null) {
                throw windowFailure;
            }
            opened.add(id + "|" + repo);
            engines.add(engine);
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String queued(Path repo) {
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-" + id,
                repo.toString().replace('\\', '/'), "appmod-harness", "copilot", T0, null));
        return id;
    }

    private String started(Path repo, SessionState end) {
        String id = queued(repo);
        sessions.markStarted(id, "window", T0.plusSeconds(1), 4242L, T0, "headsha", false);
        sessions.markFinal(id, end, T0.plusSeconds(60), 0, "endsha", "outcome", null);
        return id;
    }

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"DONE", "PARTIAL", "NEEDS_INPUT", "ERROR", "INTERRUPTED"})
    void aFinishedSessionThatStartedAnAgentOpensAWindowInItsRepository(SessionState end) {
        String id = started(repository, end);

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isTrue();
        assertThat(result.message()).contains(id).contains("unchanged");
        assertThat(opened).containsExactly(id + "|" + repository);
    }

    @Test
    void reopeningDoesNotChangeTheSessionAndAddsOneReopenedEvent() {
        String id = started(repository, SessionState.DONE);
        FixSession before = sessions.find(id).orElseThrow();

        service.reopen(id);

        FixSession after = sessions.find(id).orElseThrow();
        assertThat(after).isEqualTo(before);
        List<SessionEvent> events = sessions.events(id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).detail()).isEqualTo("reopened");
        assertThat(events.get(0).state()).isEqualTo(SessionState.DONE);
        assertThat(events.get(0).occurredAt()).isEqualTo(NOW);
    }

    @Test
    void anUnknownOrMalformedIdIsRefusedAndNothingIsOpened() {
        for (String id : new String[] {UUID.randomUUID().toString(), "not-an-id", "x'; DROP TABLE fix_session; --", null, ""}) {
            ReopenService.Result result = service.reopen(id);

            assertThat(result.opened()).as(String.valueOf(id)).isFalse();
            assertThat(result.message()).startsWith("No session");
        }
        assertThat(opened).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"QUEUED", "IN_PROGRESS"})
    void aSessionThatIsNotFinishedIsRefused(SessionState state) {
        String id = queued(repository);
        if (state == SessionState.IN_PROGRESS) {
            sessions.markStarted(id, "window", T0.plusSeconds(1), 4242L, T0, "headsha", false);
        }

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isFalse();
        assertThat(result.message()).contains(state.name()).contains("only a finished session");
        assertThat(opened).isEmpty();
        assertThat(sessions.events(id)).isEmpty();
    }

    @Test
    void aSessionThatNeverStartedAnAgentIsRefused() {
        String rejected = queued(repository);
        sessions.markFinal(rejected, SessionState.REJECTED, T0, null, null, null, "the report path must be absolute");
        String interrupted = queued(otherRepository);
        sessions.markFinal(interrupted, SessionState.INTERRUPTED, T0, null, null, null, "engine stopped before it started");

        for (String id : new String[] {rejected, interrupted}) {
            ReopenService.Result result = service.reopen(id);

            assertThat(result.opened()).isFalse();
            assertThat(result.message()).contains("never started an agent");
        }
        assertThat(opened).isEmpty();
    }

    @Test
    void aRepositoryThatIsGoneIsRefused() throws IOException {
        Path gone = Files.createDirectories(repository.resolve("gone"));
        String id = started(gone, SessionState.DONE);
        Files.delete(gone);

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isFalse();
        assertThat(result.message()).contains("no longer exists");
        assertThat(opened).isEmpty();
    }

    @Test
    void anotherSessionInTheSameRepositoryBlocksUntilItHasEnded() {
        String finished = started(repository, SessionState.DONE);
        String waiting = queued(repository);

        ReopenService.Result blocked = service.reopen(finished);

        assertThat(blocked.opened()).isFalse();
        assertThat(blocked.message()).contains("Another session is queued or in progress");
        assertThat(opened).isEmpty();

        sessions.markStarted(waiting, "window", T0.plusSeconds(5), 1L, T0, "h", false);
        assertThat(service.reopen(finished).opened()).as("still blocked while it runs").isFalse();

        sessions.markFinal(waiting, SessionState.DONE, T0.plusSeconds(90), 0, "e", "ok", null);
        assertThat(service.reopen(finished).opened()).as("free once it ended").isTrue();
    }

    @Test
    void aSessionInAnotherRepositoryDoesNotBlock() {
        String finished = started(repository, SessionState.DONE);
        queued(otherRepository);

        assertThat(service.reopen(finished).opened()).isTrue();
    }

    @Test
    void aWindowThatCannotBeOpenedIsReportedAndLeavesNoEvent() {
        String id = started(repository, SessionState.DONE);
        windowFailure = new IOException("cannot run program\u001b[31m cmd.exe");

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isFalse();
        assertThat(result.message()).startsWith("Could not open the window").doesNotContain("\u001b");
        assertThat(sessions.events(id)).isEmpty();
    }

    private String startedInWorktree(Path repo, Path work) {
        String id = queued(repo);
        sessions.setWorktree(id, new dev.subscriber.store.Worktree("shop-fix-" + id.substring(0, 8), work.toString(),
                "appmod/fix-" + id.substring(0, 8), "a".repeat(40)));
        sessions.markStarted(id, "window", T0.plusSeconds(1), 4242L, T0, "a".repeat(40), false);
        sessions.markFinal(id, SessionState.DONE, T0.plusSeconds(60), 0, "endsha", "outcome", null, 2);
        return id;
    }

    @Test
    void aSessionWithAWorktreeIsReopenedInTheWorktreeNotInTheCheckout() {
        String id = startedInWorktree(repository, otherRepository);

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isTrue();
        assertThat(opened).containsExactly(id + "|" + otherRepository);
        assertThat(result.message()).contains(otherRepository.toString());
        assertThat(sessions.find(id).orElseThrow().state()).isEqualTo(SessionState.DONE);
        assertThat(sessions.events(id)).extracting(e -> e.detail()).containsExactly("reopened");
    }

    @Test
    void aWorktreeThePersonRemovedIsReportedAndNothingIsOpened() throws IOException {
        Path gone = Files.createDirectories(otherRepository.resolve("removed-worktree"));
        String id = startedInWorktree(repository, gone);
        Files.delete(gone);

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isFalse();
        assertThat(result.message()).contains("The worktree shop-fix-" + id.substring(0, 8)).contains("no longer exists");
        assertThat(opened).isEmpty();
        assertThat(sessions.events(id)).isEmpty();
    }

    @Test
    void theSameRepositoryRuleStillLooksAtTheRepositoryNotTheWorktree() {
        String id = startedInWorktree(repository, otherRepository);
        queued(repository);

        ReopenService.Result result = service.reopen(id);

        assertThat(result.opened()).isFalse();
        assertThat(result.message()).contains("Another session is queued or in progress");
        assertThat(opened).isEmpty();
    }

    @Test
    void theWindowIsOpenedForTheToolThatRanTheSessionWhateverTheConfigurationSaysNow() {
        String copilot = started(repository, SessionState.DONE);
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-" + id,
                otherRepository.toString().replace('\\', '/'), "security-fixer", "claude", T0, null));
        sessions.markStarted(id, "window", T0.plusSeconds(1), 4242L, T0, "headsha", false);
        sessions.markFinal(id, SessionState.DONE, T0.plusSeconds(60), 0, "endsha", "outcome", null);

        service.reopen(copilot);
        service.reopen(id);

        assertThat(engines).containsExactly("copilot", "claude");
    }
}
