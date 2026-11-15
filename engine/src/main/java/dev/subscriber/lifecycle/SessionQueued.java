package dev.subscriber.lifecycle;

/** Published when a session was queued. The dispatcher reacts to it once the transaction that recorded the session has committed. */
public record SessionQueued(String sessionId) {
}
