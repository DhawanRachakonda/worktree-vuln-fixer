package dev.intel.store;

import java.time.Instant;

/**
 * One ingestion run as recorded in history.
 *
 * @param itemsSeen    items found in the feed, including the skipped ones
 * @param itemsSkipped malformed items that could not be stored (no title, identity or usable link)
 */
public record IngestionRun(
        String id,
        String sourceName,
        IngestionTrigger trigger,
        Instant startedAt,
        Instant finishedAt,
        IngestionOutcome outcome,
        int itemsSeen,
        int itemsNew,
        int itemsUpdated,
        int itemsUnchanged,
        int itemsSkipped,
        String errorReason) {
}
