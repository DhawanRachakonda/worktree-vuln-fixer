package dev.subscriber.console;

import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.worktree.WorktreeProvisioner;
import dev.subscriber.worktree.WorktreeProvisioner.WorktreeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Removes the worktree folder of a finished session when an operator asks for it, and only then. Without the confirmation it
 * removes nothing and says what it would do. The branch and its commits are never touched, and the session's state and other
 * records stay; the timeline gets one "worktree removed" event.
 */
@Component
public class CleanupService {

    /** What the console prints, and whether a worktree was removed. */
    public record Result(boolean removed, String message) {
    }

    private final FixSessionRepository sessions;
    private final WorktreeProvisioner worktrees;
    private final Clock clock;

    @Autowired
    public CleanupService(FixSessionRepository sessions, WorktreeProvisioner worktrees, Clock clock) {
        this.sessions = sessions;
        this.worktrees = worktrees;
        this.clock = clock;
    }

    public Result cleanup(String id, boolean confirmed) {
        // Only a UUID can name a session; anything else is not looked up.
        FixSession session = id != null && id.matches("[0-9a-fA-F-]{36}") ? sessions.find(id.toLowerCase(Locale.ROOT)).orElse(null)
                : null;
        if (session == null) {
            return refused("No session '%s'.".formatted(Text.printable(id, 40)));
        }
        if (!session.state().isFinal()) {
            return refused("Session %s is %s; only a finished session's worktree can be removed.".formatted(session.id(), session.state()));
        }
        if (!session.hasWorktree()) {
            return refused("Session %s ran in the repository's own checkout and has no worktree to remove.".formatted(session.id()));
        }
        String folder = Text.printable(session.workPath(), 120);
        if (!Files.isDirectory(Path.of(session.workPath()))) {
            return refused("The worktree %s (%s) is already gone.".formatted(Text.printable(session.workName(), 120), folder));
        }
        if (sessions.hasActiveForRepository(session.repositoryPath())) {
            return refused("Another session is queued or in progress in %s; remove this worktree when it has ended."
                    .formatted(Text.printable(session.repositoryPath(), 120)));
        }
        String kept = "The branch %s is kept with its commits%s."
                .formatted(Text.printable(session.workBranch(), 60), commits(session));
        if (!confirmed) {
            return new Result(false, "Would remove the worktree folder %s. %s Anything the agent reported under .appmod/ in it goes too. "
                    .formatted(folder, kept) + "Nothing was removed; run `cleanup %s --yes` to remove it.".formatted(session.id()));
        }
        try {
            worktrees.remove(Path.of(session.repositoryPath()), session.workPath(), session.workBranch());
        } catch (WorktreeException e) {
            return refused("Not removed: " + Text.printable(e.getMessage(), 240));
        }
        sessions.appendEvent(session.id(), clock.instant(), session.state(), "worktree removed");
        return new Result(true, "Removed the worktree folder %s. %s The session's state is unchanged.".formatted(folder, kept));
    }

    private static String commits(FixSession session) {
        Integer count = session.fixCommits();
        return count == null ? "" : " (%d commit%s)".formatted(count, count == 1 ? "" : "s");
    }

    private static Result refused(String message) {
        return new Result(false, message);
    }
}
