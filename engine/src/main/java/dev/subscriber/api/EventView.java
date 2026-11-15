package dev.subscriber.api;

import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.time.Instant;

/** One state a session entered, with when and a short note. */
public record EventView(Instant occurredAt, SessionState state, String detail) {

    static EventView of(SessionEvent e) {
        return new EventView(e.occurredAt(), e.state(), e.detail());
    }
}
