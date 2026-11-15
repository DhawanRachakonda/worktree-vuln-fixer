package dev.subscriber.store;

/** Where a fix session is in its life. The last six are final: they never change again. */
public enum SessionState {
    QUEUED,
    IN_PROGRESS,
    DONE,
    PARTIAL,
    NEEDS_INPUT,
    ERROR,
    INTERRUPTED,
    REJECTED;

    public boolean isFinal() {
        return this != QUEUED && this != IN_PROGRESS;
    }
}
