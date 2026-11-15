package dev.intel.query;

import java.time.Instant;

/**
 * How current the stored data is, so that an empty answer is never mistaken for "no vulnerabilities".
 *
 * @param lastSuccessfulIngestionAt when the feed was last read successfully; null when that never happened
 * @param stale                     true when that time is older than the configured threshold, or does not exist
 * @param status                    {@code OK}, {@code STALE}, or {@code NEVER_INGESTED}
 */
public record Freshness(Instant lastSuccessfulIngestionAt, boolean stale, Status status) {

    public enum Status {
        OK,
        STALE,
        NEVER_INGESTED
    }
}
