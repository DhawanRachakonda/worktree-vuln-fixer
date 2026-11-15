package dev.subscriber.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionState;
import dev.subscriber.store.Worktree;
import dev.subscriber.worktree.WorktreeProvisioner;
import java.io.IOException;
import java.io.UncheckedIOException;
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
class CleanupServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-10T12:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-13T09:00:00Z");

    @Autowired
    FixSessionRepository sessions;

    @TempDir
    Path repository;

    @TempDir
    Path otherRepository;

    private final List<String> removed = new ArrayList<>();
    private String refusal;
    private CleanupService service;

    @BeforeEach
    void service() {
        WorktreeProvisioner worktrees = new WorktreeProvisioner() {
            @Override
            public Created create(String sessionId, Path repo, Path report) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Integer commitCount(String workPath, String baseCommit, String branch) {
                return null;
            }

            @Override
            public void remove(Path repo, String workPath, String branch) throws WorktreeException {
                if (refusal != null) {
                    throw new WorktreeException(refusal);
                }
                try {
                    Files.deleteIfExists(Path.of(workPath));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                removed.add(repo + "|" + workPath + "|" + branch);
            }
        };
        service = new CleanupService(sessions, worktrees, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private String queued(Path repo) {
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-" + id,
                repo.toString().replace('\\', '/'), "security-fixer", "copilot", T0, null));
        return id;
    }

    /** A finished session with a worktree folder that exists. */
    private String finished(Path repo, Path work, SessionState end) {
        String id = queued(repo);
        sessions.setWorktree(id, new Worktree("shop-fix-" + id.substring(0, 8), work.toString().replace('\\', '/'),
                "appmod/fix-" + id.substring(0, 8), "a".repeat(40)));
        sessions.markStarted(id, "window", T0.plusSeconds(1), 4242L, T0, "a".repeat(40), false);
        sessions.markFinal(id, end, T0.plusSeconds(60), 0, "endsha", "outcome", null, 4);
        return id;
    }

    private Path workFolder() throws IOException {
        return Files.createDirectories(otherRepository.resolve("shop-fix-work"));
    }

    @Test
    void withoutTheConfirmationNothingIsRemovedAndTheAnswerSaysWhatWouldHappen() throws IOException {
        Path work = workFolder();
        String id = finished(repository, work, SessionState.DONE);

        CleanupService.Result result = service.cleanup(id, false);

        assertThat(result.removed()).isFalse();
        assertThat(result.message()).contains("Would remove the worktree folder").contains(work.toString().replace('\\', '/'))
                .contains("appmod/fix-" + id.substring(0, 8) + " is kept with its commits (4 commits)")
                .contains("cleanup " + id + " --yes");
        assertThat(removed).isEmpty();
        assertThat(work).exists();
        assertThat(sessions.events(id)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"DONE", "PARTIAL", "NEEDS_INPUT", "ERROR", "INTERRUPTED"})
    void aConfirmedCleanupRemovesTheFolderAddsOneEventAndLeavesTheStateAlone(SessionState end) throws IOException {
        Path work = workFolder();
        String id = finished(repository, work, end);

        CleanupService.Result result = service.cleanup(id, true);

        assertThat(result.removed()).isTrue();
        assertThat(result.message()).contains("Removed the worktree folder").contains("is kept with its commits");
        assertThat(removed).containsExactly(repository + "|" + work.toString().replace('\\', '/')
                + "|appmod/fix-" + id.substring(0, 8));
        assertThat(work).doesNotExist();
        assertThat(sessions.find(id).orElseThrow().state()).isEqualTo(end);
        assertThat(sessions.events(id)).extracting(e -> e.detail()).containsExactly("worktree removed");
        assertThat(sessions.events(id)).extracting(e -> e.state()).containsExactly(end);
    }

    @Test
    void aSecondCleanupSaysTheFolderIsAlreadyGone() throws IOException {
        String id = finished(repository, workFolder(), SessionState.DONE);
        service.cleanup(id, true);

        CleanupService.Result again = service.cleanup(id, true);

        assertThat(again.removed()).isFalse();
        assertThat(again.message()).contains("already gone");
        assertThat(sessions.events(id)).hasSize(1);
    }

    @Test
    void theRefusalOfTheWorktreeServiceIsShownAndLeavesNoEvent() throws IOException {
        String id = finished(repository, workFolder(), SessionState.DONE);
        refusal = "the worktree has uncommitted work (1 file, for example a.txt)\u001b[31m";

        CleanupService.Result result = service.cleanup(id, true);

        assertThat(result.removed()).isFalse();
        assertThat(result.message()).startsWith("Not removed: the worktree has uncommitted work").doesNotContain("\u001b");
        assertThat(sessions.events(id)).isEmpty();
    }

    @Test
    void anUnknownOrMalformedIdIsRefused() {
        assertThat(service.cleanup("nope", true).message()).isEqualTo("No session 'nope'.");
        assertThat(service.cleanup(null, true).removed()).isFalse();
        assertThat(service.cleanup(UUID.randomUUID().toString(), true).message()).startsWith("No session");
        assertThat(removed).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = SessionState.class, names = {"QUEUED", "IN_PROGRESS"})
    void aSessionThatIsNotFinishedIsRefused(SessionState state) throws IOException {
        Path work = workFolder();
        String id = queued(repository);
        sessions.setWorktree(id, new Worktree("shop-fix-x", work.toString(), "appmod/fix-" + id.substring(0, 8), "a".repeat(40)));
        if (state == SessionState.IN_PROGRESS) {
            sessions.markStarted(id, "window", T0.plusSeconds(1), 1L, T0, "h", false);
        }

        CleanupService.Result result = service.cleanup(id, true);

        assertThat(result.removed()).isFalse();
        assertThat(result.message()).contains("only a finished session's worktree");
        assertThat(work).exists();
    }

    @Test
    void aSessionThatRanInTheCheckoutHasNothingToRemove() {
        String id = queued(repository);
        sessions.markStarted(id, "window", T0.plusSeconds(1), 1L, T0, "h", false);
        sessions.markFinal(id, SessionState.DONE, T0.plusSeconds(60), 0, "e", "ok", null);

        CleanupService.Result result = service.cleanup(id, true);

        assertThat(result.removed()).isFalse();
        assertThat(result.message()).contains("own checkout");
    }

    @Test
    void anotherSessionInTheSameRepositoryBlocksUntilItHasEnded() throws IOException {
        Path work = workFolder();
        String finished = finished(repository, work, SessionState.DONE);
        String waiting = queued(repository);

        assertThat(service.cleanup(finished, true).message()).contains("Another session is queued or in progress");
        assertThat(work).exists();

        sessions.markStarted(waiting, "window", T0.plusSeconds(5), 1L, T0, "h", false);
        assertThat(service.cleanup(finished, true).removed()).as("still blocked while it runs").isFalse();

        sessions.markFinal(waiting, SessionState.DONE, T0.plusSeconds(90), 0, "e", "ok", null);
        assertThat(service.cleanup(finished, true).removed()).as("free once it ended").isTrue();
    }

    @Test
    void aSessionInAnotherRepositoryDoesNotBlock() throws IOException {
        String finished = finished(repository, workFolder(), SessionState.DONE);
        queued(otherRepository);

        assertThat(service.cleanup(finished, true).removed()).isTrue();
    }
}
