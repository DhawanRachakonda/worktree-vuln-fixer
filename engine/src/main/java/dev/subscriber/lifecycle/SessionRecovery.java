package dev.subscriber.lifecycle;

import dev.subscriber.runner.ExitRecord;
import dev.subscriber.runner.ProcessToken;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Sorts out what a restart left behind, before any new session is started:
 * <ul>
 * <li>a session that was {@code QUEUED} simply stays queued and runs in its original order (the dispatcher reads the queue from the
 * database);</li>
 * <li>an {@code IN_PROGRESS} session whose exit record exists is finalized from it, as if the engine had been running;</li>
 * <li>one whose window is still running is followed again until it ends;</li>
 * <li>one whose window is gone and left no record becomes {@code INTERRUPTED}.</li>
 * </ul>
 */
@Component
public class SessionRecovery {

    private static final Logger log = LoggerFactory.getLogger(SessionRecovery.class);

    private final FixSessionRepository sessions;
    private final SessionFolders folders;
    private final SessionLifecycle lifecycle;
    private final SessionDispatcher dispatcher;
    private final Duration pollEvery;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("session-recovery-poller").daemon().factory());

    @Autowired
    public SessionRecovery(FixSessionRepository sessions, SessionFolders folders, SessionLifecycle lifecycle,
            SessionDispatcher dispatcher) {
        this(sessions, folders, lifecycle, dispatcher, Duration.ofSeconds(2));
    }

    SessionRecovery(FixSessionRepository sessions, SessionFolders folders, SessionLifecycle lifecycle, SessionDispatcher dispatcher,
            Duration pollEvery) {
        this.sessions = sessions;
        this.folders = folders;
        this.lifecycle = lifecycle;
        this.dispatcher = dispatcher;
        this.pollEvery = pollEvery;
    }

    /** At startup: recover first, then let the dispatcher look at the queue. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        recover();
        dispatcher.kick();
    }

    void recover() {
        int queued = sessions.queued().size();
        if (queued > 0) {
            log.info("{} queued session(s) from before the restart will run in their original order", queued);
        }
        for (FixSession session : sessions.inProgress()) {
            try {
                recover(session);
            } catch (RuntimeException e) {
                log.error("Session {} could not be recovered: {}", session.id(), e.toString());
            }
        }
    }

    private void recover(FixSession session) {
        Path folder = folders.folder(session.id());
        if (ExitRecord.read(folder).isPresent()) {
            log.info("Session {} ended while the engine was down; finalizing it from its exit record", session.id());
            lifecycle.finish(session.id());
            lifecycle.publishIfAsked(session.id());
            return;
        }
        ProcessToken token = new ProcessToken(session.pid() == null ? -1 : session.pid(), session.pidStartedAt());
        if (session.pid() != null && token.isAlive()) {
            log.info("Session {} is still running; following it again", session.id());
            dispatcher.adopt(session.id(), session.repositoryPath(), watch(session.id(), token));
            return;
        }
        lifecycle.interrupted(session.id(), "the window was gone after a restart and left no exit record");
    }

    /** A future that completes when the session's exit record appears or its process is gone. */
    private CompletableFuture<Void> watch(String sessionId, ProcessToken token) {
        CompletableFuture<Void> ended = new CompletableFuture<>();
        Path folder = folders.folder(sessionId);
        ScheduledFuture<?> task = poller.scheduleWithFixedDelay(() -> {
            if (ExitRecord.read(folder).isPresent() || !token.isAlive()) {
                ended.complete(null);
            }
        }, pollEvery.toMillis(), pollEvery.toMillis(), TimeUnit.MILLISECONDS);
        ended.whenComplete((_, _) -> task.cancel(false));
        return ended;
    }

    @PreDestroy
    void stop() {
        poller.shutdownNow();
    }
}
