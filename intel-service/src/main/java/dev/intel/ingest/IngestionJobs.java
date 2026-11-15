package dev.intel.ingest;

import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionTrigger;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.context.JobContext;
import org.springframework.stereotype.Component;

/**
 * The JobRunr job methods. Each runs one ingestion and writes one line to the job's own log, which JobRunr's dashboard
 * shows next to the job's state: the run id with its counts, or the reason it failed. When the run is recorded as failed
 * or refused the method throws, so JobRunr marks the job failed and retries it a few times with its back-off. A later
 * scheduled run tries again anyway.
 *
 * <p>The {@link JobContext} parameter is supplied by JobRunr when the job runs; callers pass {@link JobContext#Null}.
 */
@Component
public class IngestionJobs {

    private final IngestionService ingestion;

    public IngestionJobs(IngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @Job(name = "Zero Wire ingestion", retries = 3)
    public void scheduledIngestion(JobContext jobContext) {
        execute(IngestionTrigger.SCHEDULED, jobContext);
    }

    @Job(name = "Zero Wire ingestion (start-up catch-up)", retries = 3)
    public void catchUpIngestion(JobContext jobContext) {
        execute(IngestionTrigger.CATCH_UP, jobContext);
    }

    @Job(name = "Zero Wire ingestion (manual)", retries = 0)
    public void manualIngestion(JobContext jobContext) {
        execute(IngestionTrigger.MANUAL, jobContext);
    }

    private void execute(IngestionTrigger trigger, JobContext jobContext) {
        IngestionRun run = ingestion.run(trigger);
        if (run.outcome().successful()) {
            jobContext.logger().info(summary(run));
            return;
        }
        jobContext.logger().error(summary(run));
        throw new IngestionFailedException(run);
    }

    /** The line shown in the dashboard: no feed text, only the run id, the outcome, the counts or the failure reason. */
    static String summary(IngestionRun run) {
        if (run.outcome().successful()) {
            return "Run " + run.id() + ": " + run.outcome() + " - seen " + run.itemsSeen() + ", new " + run.itemsNew()
                    + ", updated " + run.itemsUpdated() + ", unchanged " + run.itemsUnchanged() + ", skipped "
                    + run.itemsSkipped();
        }
        return "Run " + run.id() + ": " + run.outcome() + " - " + run.errorReason();
    }
}
