package dev.subscriber.runner;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * A session that was started: the process that was launched (for the window runner, the wrapper that waits for the window) and a
 * future that completes when it ends. The pid together with its start time identifies the process, because ids are reused.
 */
public interface RunningSession {

    long pid();

    Instant pidStartedAt();

    /** Completes, whatever the exit code, when the launched process has ended. */
    CompletableFuture<Void> ended();
}
