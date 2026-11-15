package dev.subscriber.lifecycle;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.runner.RepositoryProbe;
import dev.subscriber.runner.RepositoryProbe.RepositoryState;
import dev.subscriber.runner.RunningSession;
import dev.subscriber.runner.SessionLauncher;
import dev.subscriber.session.PromptWriter;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.Worktree;
import dev.subscriber.worktree.WorktreeProvisioner;
import dev.subscriber.worktree.WorktreeProvisioner.WorktreeException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts queued sessions and follows them until they end.
 *
 * <p>The rules: the oldest queued session goes first; at most {@code subscriber.max-parallel} sessions run at once; two sessions for
 * the same repository never run together (a session whose repository is busy is skipped for now, so it does not hold up the others,
 * and keeps its place); a session that cannot be started becomes {@code ERROR} and the queue goes on; a session that ends, in any
 * state, frees its slot at once.
 *
 * <p>Every decision runs on one scheduler thread, so the list of running sessions needs no locking.
 */
@Component
public class SessionDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SessionDispatcher.class);

    private final SubscriberProperties properties;
    private final FixSessionRepository sessions;
    private final SessionLifecycle lifecycle;
    private final SessionLauncher launcher;
    private final RepositoryProbe probe;
    private final WorktreeProvisioner worktrees;
    private final PromptWriter prompts;
    private final ExecutorService scheduler;

    /** Session id to repository path, for the sessions this engine is following. Touched only on the scheduler thread. */
    private final Map<String, String> running = new LinkedHashMap<>();
    private volatile boolean stopped;

    /** For sessions that work in the repository's own checkout: no worktrees are made. */
    public SessionDispatcher(SubscriberProperties properties, FixSessionRepository sessions, SessionLifecycle lifecycle,
            SessionLauncher launcher, RepositoryProbe probe) {
        this(properties, sessions, lifecycle, launcher, probe, null, null);
    }

    @Autowired
    public SessionDispatcher(SubscriberProperties properties, FixSessionRepository sessions, SessionLifecycle lifecycle,
            SessionLauncher launcher, RepositoryProbe probe, WorktreeProvisioner worktrees, PromptWriter prompts) {
        this(properties, sessions, lifecycle, launcher, probe, worktrees, prompts,
                Executors.newSingleThreadExecutor(Thread.ofPlatform().name("session-dispatcher").daemon().factory()));
    }

    SessionDispatcher(SubscriberProperties properties, FixSessionRepository sessions, SessionLifecycle lifecycle,
            SessionLauncher launcher, RepositoryProbe probe, ExecutorService scheduler) {
        this(properties, sessions, lifecycle, launcher, probe, null, null, scheduler);
    }

    SessionDispatcher(SubscriberProperties properties, FixSessionRepository sessions, SessionLifecycle lifecycle,
            SessionLauncher launcher, RepositoryProbe probe, WorktreeProvisioner worktrees, PromptWriter prompts,
            ExecutorService scheduler) {
        this.properties = properties;
        this.sessions = sessions;
        this.lifecycle = lifecycle;
        this.launcher = launcher;
        this.probe = probe;
        this.worktrees = worktrees;
        this.prompts = prompts;
        this.scheduler = scheduler;
    }

    /** Asks the scheduler to look for work. Safe to call from any thread, as often as needed. */
    public void kick() {
        if (stopped) {
            return;
        }
        try {
            scheduler.execute(this::dispatch);
        } catch (RejectedExecutionException _) {
            log.debug("The dispatcher is shutting down");
        }
    }

    /** A session was queued and its record is committed: look for work. */
    @TransactionalEventListener(fallbackExecution = true)
    void onQueued(SessionQueued event) {
        kick();
    }

    @PreDestroy
    public void stop() {
        stopped = true;
        scheduler.shutdown();
    }

    /** Sessions that are being followed right now (runs on the scheduler thread; used by tests and recovery). */
    int runningCount() {
        return running.size();
    }

    private void dispatch() {
        try {
            for (FixSession session : sessions.queued()) {
                if (running.size() >= properties.maxParallel()) {
                    return;
                }
                if (running.containsValue(session.repositoryPath())) {
                    continue;
                }
                start(session);
            }
        } catch (RuntimeException e) {
            log.error("The dispatcher could not look at the queue: {}", e.toString());
        }
    }

    private void start(FixSession session) {
        Path repository = Path.of(session.repositoryPath());
        RepositoryState state = probe.probe(repository);
        Path workingDirectory = repository;
        String headStart = state.head();
        if (isolation(session) == SubscriberProperties.Isolation.WORKTREE) {
            Worktree worktree = prepareWorktree(session, repository);
            if (worktree == null) {
                return;
            }
            workingDirectory = Path.of(worktree.path());
            headStart = worktree.baseCommit();
        }
        RunningSession handle;
        try {
            handle = launcher.launch(session.id(), workingDirectory, session.agent());
        } catch (IOException | RuntimeException e) {
            lifecycle.error(session.id(), "the session could not be started: " + describe(e));
            return;
        }
        String runner = properties.effectiveRunner().name().toLowerCase(Locale.ROOT);
        if (!lifecycle.started(session.id(), runner, handle.pid(), handle.pidStartedAt(), headStart, state.dirty())) {
            log.warn("Session {} was started but is no longer queued; it is followed until it ends", session.id());
        }
        follow(session.id(), session.repositoryPath(), handle.ended());
    }

    /**
     * Creates the session's worktree, records it, and rewrites the prompt to name it. Returns null after ending the session as
     * {@code ERROR} when that is not possible, so the queue goes on.
     */
    private Worktree prepareWorktree(FixSession session, Path repository) {
        if (worktrees == null) {
            lifecycle.error(session.id(), "the worktree could not be created: worktrees are not available in this engine");
            return null;
        }
        WorktreeProvisioner.Created created;
        try {
            Path report = session.snapshotPath() == null ? null : Path.of(session.snapshotPath());
            created = worktrees.create(session.id(), repository, report,
                    new WorktreeProvisioner.SourceRequest(session.sourceRequested(), session.localChanges()));
        } catch (WorktreeException | RuntimeException e) {
            lifecycle.error(session.id(), "the worktree could not be created: " + describe(e));
            return null;
        }
        lifecycle.worktreeCreated(session.id(), created.worktree(), created.notes(), created.source());
        if (prompts != null) {
            try {
                properties.subjects().stream().filter(s -> s.subject().equals(session.subject())).findFirst().ifPresent(subject -> {
                    try {
                        prompts.write(session.id(), subject, Path.of(session.snapshotPath()), Path.of(session.report()), repository,
                                created.worktree());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            } catch (RuntimeException e) {
                lifecycle.error(session.id(), "the prompt for the worktree could not be written: " + describe(e));
                return null;
            }
        }
        return created.worktree();
    }

    /** What the session's subject says about isolation, else the general setting. */
    private SubscriberProperties.Isolation isolation(FixSession session) {
        return properties.subjects().stream().filter(s -> s.subject().equals(session.subject())).findFirst()
                .map(SubscriberProperties.Subject::isolation).orElse(properties.isolation());
    }

    /**
     * Takes over a session that was already running when the engine started (recovery): it occupies a slot and its repository until
     * it ends, exactly like a session this engine started. Runs on the scheduler thread, in order with the dispatch that follows.
     */
    void adopt(String sessionId, String repositoryPath, CompletableFuture<Void> ended) {
        try {
            scheduler.execute(() -> follow(sessionId, repositoryPath, ended));
        } catch (RejectedExecutionException _) {
            log.debug("The dispatcher is shutting down; session {} is picked up again on the next start", sessionId);
        }
    }

    /** Follows a started session; when it ends its final state is set and the next queued session may start. */
    void follow(String sessionId, String repositoryPath, CompletableFuture<Void> ended) {
        running.put(sessionId, repositoryPath);
        try {
            ended.whenCompleteAsync((_, _) -> onEnded(sessionId), scheduler);
        } catch (RejectedExecutionException _) {
            log.debug("The dispatcher is shutting down; session {} keeps running and is picked up again on the next start", sessionId);
        }
    }

    private void onEnded(String sessionId) {
        try {
            lifecycle.finish(sessionId);
            lifecycle.publishIfAsked(sessionId);
        } catch (RuntimeException e) {
            log.error("Session {} ended but its result could not be recorded: {}", sessionId, e.toString());
            try {
                lifecycle.error(sessionId, "the end of the session could not be recorded: " + describe(e));
            } catch (RuntimeException again) {
                log.error("Session {} could not be marked as failed either: {}", sessionId, again.toString());
            }
        } finally {
            running.remove(sessionId);
            dispatch();
        }
    }

    /** The cause of a failure as one line without control characters, for a reason that is stored and shown. */
    private static String describe(Throwable e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String line = message.replaceAll("\\p{Cntrl}", " ").strip();
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }
}
