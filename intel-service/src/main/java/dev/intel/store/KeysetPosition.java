package dev.intel.store;

import java.time.Instant;

/** The last row of a page: the next page starts strictly after this {@code (sortAt, id)} in descending order. */
public record KeysetPosition(Instant sortAt, String id) {
}
