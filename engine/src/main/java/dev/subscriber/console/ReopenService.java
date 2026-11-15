package dev.subscriber.console;

import dev.subscriber.runner.SessionReopener;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Reopens a finished session for a person to continue by hand, in its worktree when it had one. It refuses unless the session exists, is in a final state, did start
 * an agent, its repository is still there, and no other session is queued or running in that repository. A reopened window does not
 * change the session: its state and exit record stay, and one "reopened" event is added to its timeline.
 */
@Component
public class ReopenService {

    /** Opens the window. A separate type so the refusals can be tested without opening one. */
    @FunctionalInterface
    interface Window {
        void open(String sessionId, Path repository, String engine) throws IOException;
    }

    /** What the console prints, and whether a window was opened. */
    public record Result(boolean opened, String message) {
    }

    private final FixSessionRepository sessions;
    private final Window window;
    private final Clock clock;

    @Autowired
    public ReopenService(FixSessionRepository sessions, SessionReopener reopener, Clock clock) {
        this(sessions, reopener::reopen, clock);
    }

    ReopenService(FixSessionRepository sessions, Window window, Clock clock) {
        this.sessions = sessions;
        this.window = window;
        this.clock = clock;
    }

    public Result reopen(String id) {
        // Only a UUID can name a session; anything else is not looked up.
        FixSession session = id != null && id.matches("[0-9a-fA-F-]{36}") ? sessions.find(id.toLowerCase(Locale.ROOT)).orElse(null)
                : null;
        if (session == null) {
            return refused("No session '%s'.".formatted(Text.printable(id, 40)));
        }
        if (!session.state().isFinal()) {
            return refused("Session %s is %s; only a finished session can be reopened.".formatted(session.id(), session.state()));
        }
        if (session.startedAt() == null || session.repositoryPath() == null) {
            return refused("Session %s never started an agent (%s), so there is nothing to resume.".formatted(session.id(), session.state()));
        }
        // The conversation was held in the worktree when the session had one, so that is where it is resumed.
        Path workingFolder = Path.of(session.workingPath());
        if (!Files.isDirectory(workingFolder)) {
            return refused(session.hasWorktree()
                    ? "The worktree %s (%s) no longer exists.".formatted(Text.printable(session.workName(), 120),
                            Text.printable(session.workPath(), 120))
                    : "The repository %s no longer exists.".formatted(Text.printable(session.repositoryPath(), 120)));
        }
        if (sessions.hasActiveForRepository(session.repositoryPath())) {
            return refused("Another session is queued or in progress in %s; reopen this one when it has ended."
                    .formatted(Text.printable(session.repositoryPath(), 120)));
        }
        try {
            window.open(session.id(), workingFolder, session.engine());
        } catch (IOException | RuntimeException e) {
            return refused("Could not open the window: " + Text.printable(e.getMessage(), 200));
        }
        sessions.appendEvent(session.id(), clock.instant(), session.state(), "reopened");
        return new Result(true, "Opened a window that resumes session %s in %s. The session's state is unchanged."
                .formatted(session.id(), Text.printable(session.workingPath(), 120)));
    }

    private static Result refused(String message) {
        return new Result(false, message);
    }
}
