package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.store.IngestionOutcome;
import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionRunRepository;
import dev.intel.store.IngestionTrigger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.jobrunr.jobs.RecurringJob;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real JobRunr storage with the background server switched off: jobs are stored and can be inspected, but nothing is
 * executed and nothing is fetched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:scheduling;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false",
        "intel.schedule.cron=30 1 * * *",
        "intel.schedule.zone=Asia/Kolkata"
})
@Import(IngestionSchedulingTest.TestClock.class)
class IngestionSchedulingTest {

    static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    @TestConfiguration
    static class TestClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    @Autowired
    IngestionScheduling scheduling;

    @Autowired
    StorageProvider storage;

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanSlate() {
        clock.set(T0);
        jdbc.sql("DELETE FROM jobrunr_jobs").update();
        jdbc.sql("DELETE FROM ingestion_run").update();
    }

    private long enqueuedJobs() {
        return storage.countJobs(StateName.ENQUEUED);
    }

    private void recordRun(IngestionOutcome outcome, Instant finished) {
        runs.insert(new IngestionRun(UUID.randomUUID().toString(), IngestionService.SOURCE_NAME,
                IngestionTrigger.SCHEDULED, finished.minusSeconds(5), finished, outcome, 8, 0, 0, 8, 0,
                outcome.successful() ? null : "failed: test"));
    }

    // ------------------------------------------------------------------ the daily job

    @Test
    void theDailyJobIsRegisteredFromTheConfiguredCronAndTimeZone() {
        scheduling.registerRecurringJob();

        assertThat(storage.getRecurringJobs()).hasSize(1);
        RecurringJob job = storage.getRecurringJobs().get(0);
        assertThat(job.getId()).isEqualTo(IngestionScheduling.RECURRING_JOB_ID);
        assertThat(job.getScheduleExpression()).isEqualTo("30 1 * * *");
        assertThat(job.getZoneId()).isEqualTo("Asia/Kolkata");
        assertThat(job.getJobName()).isEqualTo("Zero Wire ingestion");
    }

    @Test
    void registeringAgainUpdatesTheStoredJobInsteadOfAddingAnother() {
        scheduling.registerRecurringJob();
        scheduling.registerRecurringJob();
        scheduling.registerRecurringJob();

        assertThat(storage.getRecurringJobs()).hasSize(1);
    }

    @Test
    void theFirstScheduledRunIsTheNextCronTimeNotTheStartup() {
        scheduling.registerRecurringJob();

        RecurringJob job = storage.getRecurringJobs().get(0);
        Instant nextRun = job.getNextRun();
        ZonedDateTime local = ZonedDateTime.ofInstant(nextRun, ZoneId.of("Asia/Kolkata"));
        assertThat(nextRun).isAfter(Instant.now());
        assertThat(nextRun).isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(24)));
        assertThat(local.toLocalTime()).isEqualTo(LocalTime.of(1, 30));
    }

    @Test
    void theJobMethodsCarryTheRetryPolicyThatRetriesAFailedRun() throws Exception {
        assertThat(IngestionJobs.class.getMethod("scheduledIngestion", org.jobrunr.jobs.context.JobContext.class)
                .getAnnotation(org.jobrunr.jobs.annotations.Job.class).retries()).isEqualTo(3);
        assertThat(IngestionJobs.class.getMethod("catchUpIngestion", org.jobrunr.jobs.context.JobContext.class)
                .getAnnotation(org.jobrunr.jobs.annotations.Job.class).retries()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ start-up catch-up

    @Test
    void anEmptyStoreNeedsOneCatchUpAndAskingAgainInTheSameHourDoesNotAddAnother() {
        assertThat(scheduling.enqueueCatchUpIfNeeded()).isTrue();
        assertThat(enqueuedJobs()).isEqualTo(1);

        assertThat(scheduling.enqueueCatchUpIfNeeded()).isTrue();
        clock.set(T0.plus(Duration.ofMinutes(40)));
        assertThat(scheduling.enqueueCatchUpIfNeeded()).isTrue();

        assertThat(enqueuedJobs()).as("same state, same hour: the same job").isEqualTo(1);
    }

    @Test
    void aLaterHourEnqueuesAFreshCatchUpSoAFailedOneCanBeRetriedByARestart() {
        scheduling.enqueueCatchUpIfNeeded();
        clock.set(T0.plus(Duration.ofHours(2)));
        scheduling.enqueueCatchUpIfNeeded();

        assertThat(enqueuedJobs()).isEqualTo(2);
    }

    @Test
    void aSuccessOlderThanTheThresholdNeedsACatchUp() {
        recordRun(IngestionOutcome.SUCCESS, T0.minus(Duration.ofHours(30)));

        assertThat(scheduling.enqueueCatchUpIfNeeded()).isTrue();
        assertThat(enqueuedJobs()).isEqualTo(1);
    }

    @Test
    void aRecentSuccessNeedsNoCatchUp() {
        recordRun(IngestionOutcome.SUCCESS, T0.minus(Duration.ofHours(2)));

        assertThat(scheduling.enqueueCatchUpIfNeeded()).isFalse();
        assertThat(enqueuedJobs()).isZero();
    }

    @Test
    void theThresholdIsExclusive() {
        recordRun(IngestionOutcome.NO_CHANGE, T0.minus(Duration.ofHours(24)));
        assertThat(scheduling.enqueueCatchUpIfNeeded()).as("exactly 24 hours old").isFalse();

        clock.set(T0.plusSeconds(1));
        assertThat(scheduling.enqueueCatchUpIfNeeded()).as("24 hours and a second old").isTrue();
    }

    @Test
    void historyWithOnlyFailuresStillNeedsACatchUp() {
        recordRun(IngestionOutcome.FAILED, T0.minus(Duration.ofHours(1)));
        recordRun(IngestionOutcome.REFUSED, T0.minus(Duration.ofMinutes(10)));

        assertThat(scheduling.enqueueCatchUpIfNeeded()).isTrue();
    }

    @Test
    void theCatchUpJobIdDependsOnTheLastSuccessAndTheHour() {
        Instant success = T0.minus(Duration.ofHours(30));
        UUID base = IngestionScheduling.catchUpJobId(java.util.Optional.of(success), T0);

        assertThat(IngestionScheduling.catchUpJobId(java.util.Optional.of(success), T0.plusSeconds(59 * 60)))
                .isEqualTo(base);
        assertThat(IngestionScheduling.catchUpJobId(java.util.Optional.of(success), T0.plus(Duration.ofHours(1))))
                .isNotEqualTo(base);
        assertThat(IngestionScheduling.catchUpJobId(java.util.Optional.empty(), T0)).isNotEqualTo(base);
    }
}
