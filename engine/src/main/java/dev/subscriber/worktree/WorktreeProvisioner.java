package dev.subscriber.worktree;

import dev.subscriber.store.Worktree;
import java.nio.file.Path;
import java.util.List;

/** Creates the worktree of a session and counts what the agent committed in it. */
public interface WorktreeProvisioner {

    /** The worktree that was created and what to tell the person about how its base commit was chosen. */
    record Created(Worktree worktree, List<String> notes, Source source) {

        public Created(Worktree worktree, List<String> notes) {
            this(worktree, notes, Source.UNKNOWN);
        }
    }

    /** What the person asked for when publishing the report: a source branch, or the local changes of the checkout (not both). */
    record SourceRequest(String branch, boolean localChanges) {

        public static final SourceRequest NONE = new SourceRequest(null, false);
    }

    /** Where the worktree started: the branch (null when it is not a branch tip), which choice applied, and whether local changes were included. */
    record Source(String branch, String choice, boolean localChanges) {

        public static final Source UNKNOWN = new Source(null, null, false);
    }

    /** Why a worktree could not be created. The text is shown to the operator and stored as the session's reason. */
    final class WorktreeException extends Exception {

        private static final long serialVersionUID = 1L;

        public WorktreeException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * @param sessionId  the session id (a UUID)
     * @param repository the repository's real path
     * @param report     the engine's copy of the report (a CSV may name the commit it audited); may be null
     */
    Created create(String sessionId, Path repository, Path report) throws WorktreeException;

    /** As above, for a report published with a source branch or with the option to include local changes. */
    default Created create(String sessionId, Path repository, Path report, SourceRequest request) throws WorktreeException {
        return create(sessionId, repository, report);
    }

    /** How many commits were made on the branch since the base commit; null when that cannot be told. */
    Integer commitCount(String workPath, String baseCommit, String branch);

    /**
     * Removes the worktree folder of a finished session, and nothing else: the branch and its commits stay. Refuses (with the reason
     * as the exception's text) unless the folder is a worktree of the repository that has the form the engine made and holds no
     * uncommitted work other than the agent's reports under {@code .appmod/}.
     *
     * @param repository the session's repository
     * @param workPath   the recorded worktree folder
     * @param branch     the recorded branch
     */
    void remove(Path repository, String workPath, String branch) throws WorktreeException;
}
