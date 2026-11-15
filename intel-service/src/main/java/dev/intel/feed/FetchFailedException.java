package dev.intel.feed;

import dev.intel.store.IngestionOutcome;

/** The destination was allowed but the fetch failed: network error, timeout, bad status, size or content type. */
public final class FetchFailedException extends FetchException {

    public FetchFailedException(String reason) {
        this(reason, null);
    }

    public FetchFailedException(String reason, Throwable cause) {
        super("failed: " + reason, cause);
    }

    @Override
    public IngestionOutcome outcome() {
        return IngestionOutcome.FAILED;
    }
}
