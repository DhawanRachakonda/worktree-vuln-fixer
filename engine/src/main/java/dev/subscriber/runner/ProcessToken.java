package dev.subscriber.runner;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Identifies a process: its id and the time it started. An id alone is not enough, because the operating system reuses ids; a live
 * process with the same id but another start time is a different process.
 */
public record ProcessToken(long pid, Instant startedAt) {

    /** Start times are stored with less precision than the operating system reports, so they are compared with a margin. */
    static final Duration TOLERANCE = Duration.ofSeconds(2);

    /** True when this process still runs. */
    public boolean isAlive() {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        Optional<Instant> actual = handle.get().info().startInstant();
        // When the system will not say when a live process started, it cannot be told apart from a reused id: not the same process.
        return actual.isPresent() && startedAt != null
                && Duration.between(startedAt, actual.get()).abs().compareTo(TOLERANCE) <= 0;
    }
}
