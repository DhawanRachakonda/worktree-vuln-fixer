package dev.subscriber.store;

import java.time.Instant;

/** One row of {@code fix_session_event}: a state a session entered, with the time and a short detail. */
public record SessionEvent(long id, String sessionId, Instant occurredAt, SessionState state, String detail) {
}
