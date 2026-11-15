package dev.intel.ingest;

import dev.intel.store.IngestionRun;

/** Thrown by a job when its run was recorded as failed or refused, so that JobRunr shows and retries it. */
public class IngestionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IngestionFailedException(IngestionRun run) {
        super(run.outcome() + ": " + run.errorReason());
    }
}
