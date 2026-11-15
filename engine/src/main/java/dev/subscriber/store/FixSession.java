package dev.subscriber.store;

import java.time.Instant;

/**
 * One row of {@code fix_session}.
 *
 * @param seq    arrival order, assigned by the database
 * @param report the payload as received (the report path, or what was sent when the message was rejected)
 * @param workName the worktree's name, its folder, branch and base commit, and how many commits the agent made on it (counted at the
 *                 end); all empty for a session without a worktree
 */
public record FixSession(
        String id,
        long seq,
        SessionState state,
        String subject,
        String source,
        String report,
        String snapshotPath,
        String repositoryPath,
        String agent,
        String runner,
        String engine,
        Instant receivedAt,
        Instant startedAt,
        Instant endedAt,
        Long pid,
        Instant pidStartedAt,
        Integer exitCode,
        String headStart,
        String headEnd,
        Boolean dirtyAtStart,
        String outcome,
        String reason,
        String workName,
        String workPath,
        String workBranch,
        String baseCommit,
        Integer fixCommits) {

    /** Where the agent works: the session's worktree when it has one, the repository otherwise. */
    public String workingPath() {
        return workPath != null ? workPath : repositoryPath;
    }

    public boolean hasWorktree() {
        return workPath != null;
    }
}
