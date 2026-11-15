package dev.intel.feed;

import dev.intel.store.IngestionOutcome;

/** The destination was refused by policy before any data was requested from it. */
public final class FetchRefusedException extends FetchException {

    public FetchRefusedException(String reason) {
        super("refused: " + reason, null);
    }

    @Override
    public IngestionOutcome outcome() {
        return IngestionOutcome.REFUSED;
    }
}
