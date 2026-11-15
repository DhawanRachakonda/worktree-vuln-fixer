package dev.intel.ingest;

import dev.intel.config.IntelProperties;
import dev.intel.store.IngestionRunRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.scheduling.JobScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Schedules the ingestion when the application is ready.
 *
 * <ul>
 * <li>The daily job is registered under a fixed id from the configured cron expression and time zone. Registering
 * again, for example after a restart, updates the stored job instead of adding another, and the first run is the next
 * cron time, not the start-up.</li>
 * <li>JobRunr's open-source edition does not make up runs missed while the service was down. So at start-up, if no
 * ingestion has ever succeeded or the last success is older than the configured threshold, one catch-up ingestion is
 * enqueued. Its id is derived from the last success and the current hour, so restarting again within the hour does not
 * enqueue a second one. Running it twice would be harmless anyway: ingestion is idempotent.</li>
 * </ul>
 */
@Component
public class IngestionScheduling {

    public static final String RECURRING_JOB_ID = "zero-wire-ingestion";

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduling.class);

    private final JobScheduler scheduler;
    private final IngestionRunRepository runs;
    private final Clock clock;
    private final IntelProperties.Schedule schedule;

    public IngestionScheduling(JobScheduler scheduler, IngestionRunRepository runs, Clock clock,
            IntelProperties properties) {
        this.scheduler = scheduler;
        this.runs = runs;
        this.clock = clock;
        this.schedule = properties.schedule();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!schedule.enabled()) {
            log.info("Scheduling is disabled (intel.schedule.enabled=false): no daily job, no catch-up");
            return;
        }
        registerRecurringJob();
        enqueueCatchUpIfNeeded();
    }

    public void registerRecurringJob() {
        scheduler.<IngestionJobs>scheduleRecurrently(RECURRING_JOB_ID, schedule.cron(), schedule.zoneId(),
                jobs -> jobs.scheduledIngestion(JobContext.Null));
        log.info("Daily ingestion scheduled with cron '{}' in time zone {}", schedule.cron(), schedule.zone());
    }

    /**
     * Requests a catch-up ingestion when none has ever succeeded or the last success is too old. Asking again within
     * the same hour and state refers to the same job, so it is not enqueued twice.
     *
     * @return true when a catch-up was needed (and therefore requested)
     */
    public boolean enqueueCatchUpIfNeeded() {
        Instant now = clock.instant();
        Optional<Instant> lastSuccess = runs.lastSuccessfulIngestion(IngestionService.SOURCE_NAME);
        boolean needed = lastSuccess.isEmpty() || lastSuccess.get().isBefore(now.minus(schedule.catchUpAfter()));
        if (!needed) {
            return false;
        }
        UUID id = catchUpJobId(lastSuccess, now);
        scheduler.<IngestionJobs>enqueue(id, jobs -> jobs.catchUpIngestion(JobContext.Null));
        log.info("Catch-up ingestion enqueued: {}", lastSuccess.map(t -> "last success was " + t)
                .orElse("no ingestion has succeeded yet"));
        return true;
    }

    /** Same last success and same hour give the same id, which JobRunr treats as the same job. */
    static UUID catchUpJobId(Optional<Instant> lastSuccess, Instant now) {
        String key = "zero-wire-catch-up|" + lastSuccess.map(Instant::toString).orElse("never") + "|"
                + now.truncatedTo(ChronoUnit.HOURS);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
