package dev.intel.store;

import java.time.Instant;

/** Validators for conditional requests and the outcome of the latest attempt for one feed source. */
public record FeedSourceState(
        String sourceName,
        String etag,
        String lastModified,
        Instant lastAttemptAt,
        String lastError) {
}
