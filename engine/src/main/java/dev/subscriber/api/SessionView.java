package dev.subscriber.api;

import dev.subscriber.store.FixSession;
import dev.subscriber.store.SessionState;
import java.time.Instant;

/**
 * A session as the API shows it. It carries paths and reasons, never the content of a report, and leaves out the internal process
 * details (pid and its start time) that only recovery needs. The worktree fields (name, folder, branch, base commit, commits made) are
 * null for a session without a worktree.
 */
public record SessionView(
        String id,
        SessionState state,
        String subject,
        String source,
        String report,
        String repository,
        String agent,
        String runner,
        String engine,
        Instant receivedAt,
        Instant startedAt,
        Instant endedAt,
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
        Integer fixCommits,
        String sourceBranch,
        String sourceChoice,
        boolean localChanges,
        String publishState,
        String pullRequestState,
        String pullRequestLink) {

    static SessionView of(FixSession s) {
        return new SessionView(s.id(), s.state(), s.subject(), s.source(), s.report(), s.repositoryPath(), s.agent(), s.runner(),
                s.engine(), s.receivedAt(), s.startedAt(), s.endedAt(), s.exitCode(), s.headStart(), s.headEnd(), s.dirtyAtStart(),
                s.outcome(), s.reason(), s.workName(), s.workPath(), s.workBranch(), s.baseCommit(), s.fixCommits(), s.sourceBranch(), s.sourceChoice(), s.localChanges(), s.publishState(),
                s.pullRequestState(), s.pullRequestLink());
    }
}
