package dev.intel.feed;

import dev.intel.store.IngestionOutcome;

/**
 * A fetch that did not produce a feed. The message is the stable, human-readable reason that the ingestion run
 * records; it never contains feed body text.
 */
public abstract sealed class FetchException extends Exception permits FetchRefusedException, FetchFailedException {

    protected FetchException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public String reason() {
        return getMessage();
    }

    /** How the ingestion run is recorded. */
    public abstract IngestionOutcome outcome();
}
