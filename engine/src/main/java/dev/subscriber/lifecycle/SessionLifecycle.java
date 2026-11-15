package dev.subscriber.lifecycle;

import dev.subscriber.runner.ExitRecord;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionState;
import dev.subscriber.store.Worktree;
import dev.subscriber.worktree.BranchPublisher;
import dev.subscriber.worktree.WorktreeProvisioner;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves sessions through their states. Every change writes the new state and its event in one transaction, and a session that is
 * already in a final state is never changed again: the repository refuses, and no event is written for a refused change.
 */
@Service
public class SessionLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SessionLifecycle.class);

    private final FixSessionRepository sessions;
    private final SessionFolders folders;
    private final Clock clock;
    private final WorktreeProvisioner worktrees;

    private BranchPublisher publisher;

    /** Present in the running engine; without it (most tests) nothing is ever published. */
    @Autowired(required = false)
    public void setPublisher(BranchPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Called once after a session ended: pushes its branch (and opens the pull request) when the publish mode says so and the session
     * succeeded with commits. A failure is recorded by the publisher and never changes the session's state.
     */
    public void publishIfAsked(String id) {
        if (publisher == null) {
            return;
        }
        try {
            publisher.afterSession(id);
        } catch (RuntimeException e) {
            log.error("Publishing the branch of session {} failed unexpectedly: {}", id, e.toString());
        }
    }

    /** For sessions that never have a worktree: no commits are counted. */
    public SessionLifecycle(FixSessionRepository sessions, SessionFolders folders, Clock clock) {
        this(sessions, folders, clock, null);
    }

    @Autowired
    public SessionLifecycle(FixSessionRepository sessions, SessionFolders folders, Clock clock, WorktreeProvisioner worktrees) {
        this.sessions = sessions;
        this.folders = folders;
        this.clock = clock;
        this.worktrees = worktrees;
    }

    /**
     * Records the worktree of a session that is about to start, with an event for it and one for each note about how its base commit was
     * chosen. Done before the launch, so a session whose launch fails still shows where its worktree is.
     */
    /** As below, also recording where the worktree started: the source branch, the choice that applied, and local changes. */
    @Transactional
    public boolean worktreeCreated(String id, Worktree worktree, List<String> notes, WorktreeProvisioner.Source source) {
        boolean changed = worktreeCreated(id, worktree, notes);
        if (changed && source != null && source.choice() != null) {
            sessions.setSource(id, source.branch(), source.choice(), source.localChanges());
        }
        return changed;
    }

    @Transactional
    public boolean worktreeCreated(String id, Worktree worktree, List<String> notes) {
        Instant now = clock.instant();
        boolean changed = sessions.setWorktree(id, worktree);
        if (changed) {
            sessions.appendEvent(id, now, SessionState.QUEUED, "worktree created: %s on %s from %s"
                    .formatted(worktree.name(), worktree.branch(), shortCommit(worktree.baseCommit())));
            for (String note : notes) {
                sessions.appendEvent(id, now, SessionState.QUEUED, note);
            }
            log.info("Session {} works in the worktree {}", id, worktree.name());
        }
        return changed;
    }

    /** {@code QUEUED} to {@code IN_PROGRESS}. Returns false when the session was not queued. */
    @Transactional
    public boolean started(String id, String runner, long pid, Instant pidStartedAt, String headStart, Boolean dirtyAtStart) {
        Instant now = clock.instant();
        boolean changed = sessions.markStarted(id, runner, now, pid, pidStartedAt, headStart, dirtyAtStart);
        if (changed) {
            sessions.appendEvent(id, now, SessionState.IN_PROGRESS, "%s started".formatted(runner));
            log.info("Session {} is in progress ({})", id, runner);
        }
        return changed;
    }

    /**
     * Called when the launched process has ended: reads the exit record and the agent's report and sets the final state.
     *
     * @return the session's state afterwards (the final one, or the existing final state when it was already final); empty when
     *         there is no such session
     */
    @Transactional
    public Optional<SessionState> finish(String id) {
        Optional<FixSession> found = sessions.find(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        FixSession session = found.get();
        if (session.state().isFinal()) {
            return Optional.of(session.state());
        }
        Instant since = session.startedAt() != null ? session.startedAt() : session.receivedAt();
        Optional<ExitRecord> exit = ExitRecord.read(folders.folder(id));
        AgentReport.Read report = session.workingPath() == null
                ? new AgentReport.Read(AgentReport.Kind.MISSING, null)
                : AgentReport.read(Path.of(session.workingPath()), since);
        SessionOutcome outcome = SessionOutcome.derive(exit, report);

        Integer commits = countCommits(session);
        Instant now = clock.instant();
        boolean changed = sessions.markFinal(id, outcome.state(), exit.map(ExitRecord::endedAt).orElse(now),
                exit.map(ExitRecord::exitCode).orElse(null), exit.map(ExitRecord::headEnd).orElse(null), outcome.reportStatus(),
                outcome.reason(), commits);
        if (changed) {
            sessions.appendEvent(id, now, outcome.state(), outcome.reason() != null ? outcome.reason() : outcome.reportStatus());
            commitsEvent(session, outcome.state(), commits, now);
            log.info("Session {} ended as {}{}", id, outcome.state(), outcome.reason() == null ? "" : ": " + outcome.reason());
        }
        return Optional.of(sessions.find(id).orElseThrow().state());
    }

    /** A session that could not be started (or whose process cannot be followed): {@code ERROR} with the reason. */
    @Transactional
    public boolean error(String id, String reason) {
        FixSession session = sessions.find(id).orElse(null);
        Integer commits = countCommits(session);
        Instant now = clock.instant();
        boolean changed = sessions.markFinal(id, SessionState.ERROR, now, null, null, null, reason, commits);
        if (changed) {
            sessions.appendEvent(id, now, SessionState.ERROR, reason);
            commitsEvent(session, SessionState.ERROR, commits, now);
            log.warn("Session {} failed: {}", id, reason);
        }
        return changed;
    }

    /** A session whose window is gone and left no exit record: {@code INTERRUPTED}. */
    @Transactional
    public boolean interrupted(String id, String reason) {
        FixSession session = sessions.find(id).orElse(null);
        Integer commits = countCommits(session);
        Instant now = clock.instant();
        boolean changed = sessions.markFinal(id, SessionState.INTERRUPTED, now, null, null, null, reason, commits);
        if (changed) {
            sessions.appendEvent(id, now, SessionState.INTERRUPTED, reason);
            commitsEvent(session, SessionState.INTERRUPTED, commits, now);
            log.warn("Session {} was interrupted: {}", id, reason);
        }
        return changed;
    }

    /** Commits the agent made on the session's branch; null when it has no worktree or they cannot be counted. */
    private Integer countCommits(FixSession session) {
        if (session == null || !session.hasWorktree() || worktrees == null) {
            return null;
        }
        return worktrees.commitCount(session.workPath(), session.baseCommit(), session.workBranch());
    }

    private void commitsEvent(FixSession session, SessionState state, Integer commits, Instant at) {
        if (session == null || !session.hasWorktree()) {
            return;
        }
        String detail = commits == null
                ? "the commits on %s could not be counted (is the worktree folder still there?)".formatted(session.workBranch())
                : "%d %s on %s".formatted(commits, commits == 1 ? "commit" : "commits", session.workBranch());
        sessions.appendEvent(session.id(), at, state, detail);
    }

    private static String shortCommit(String commit) {
        return commit == null ? "?" : commit.substring(0, Math.min(7, commit.length()));
    }
}
