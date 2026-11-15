package dev.subscriber.runner;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/** A {@link RunningSession} backed by an operating-system process started by the engine. */
final class ProcessSession implements RunningSession {

    private final Process process;
    private final Instant startedAt;

    ProcessSession(Process process) {
        this.process = process;
        this.startedAt = process.info().startInstant().orElseGet(Instant::now);
    }

    @Override
    public long pid() {
        return process.pid();
    }

    @Override
    public Instant pidStartedAt() {
        return startedAt;
    }

    @Override
    public CompletableFuture<Void> ended() {
        return process.onExit().thenApply(p -> null);
    }
}
