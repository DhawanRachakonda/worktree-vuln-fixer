package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.intel.feed.FeedSource;
import dev.intel.feed.FetchFailedException;
import dev.intel.feed.FetchResult;
import dev.intel.store.IngestionOutcome;
import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionRunRepository;
import dev.intel.store.IngestionTrigger;
import dev.intel.store.VulnerabilityRepository;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.jobrunr.jobs.JobId;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.jobs.context.JobDashboardLogger;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.JobScheduler;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole path with the background server really running: a trigger (the dashboard's "Trigger now" over HTTP, or the
 * scheduler) starts a JobRunr job, which runs one ingestion against a scripted feed and records a run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:manual-trigger;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
        "jobrunr.jobs.retry-back-off-time-seed=1",
        "intel.schedule.enabled=false",
        "jobrunr.dashboard.enabled=true",
        "jobrunr.dashboard.username=ops",
        "jobrunr.dashboard.password=correct-horse-battery"
})
@Import(ManualTriggerTest.Scripted.class)
class ManualTriggerTest {

    private static final String AUTH = "Basic "
            + Base64.getEncoder().encodeToString("ops:correct-horse-battery".getBytes(StandardCharsets.UTF_8));
    private static final String SOURCE = IngestionService.SOURCE_NAME;
    private static final Duration PATIENCE = Duration.ofSeconds(60);
    private static final int DASHBOARD_PORT = freePort();

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        ScriptedFeedSource scriptedFeedSource() {
            return new ScriptedFeedSource();
        }
    }

    @DynamicPropertySource
    static void dashboardPort(DynamicPropertyRegistry registry) {
        registry.add("jobrunr.dashboard.port", () -> DASHBOARD_PORT);
    }

    @Autowired
    ScriptedFeedSource feed;

    @Autowired
    FeedSource feedSource;

    @Autowired
    IngestionScheduling scheduling;

    @Autowired
    JobScheduler scheduler;

    @Autowired
    StorageProvider storage;

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    VulnerabilityRepository vulnerabilities;

    @Autowired
    JdbcClient jdbc;

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void cleanSlate() {
        feed.clear();
        jdbc.sql("DELETE FROM vulnerability").update();
        jdbc.sql("DELETE FROM ingestion_run").update();
        jdbc.sql("DELETE FROM feed_source_state").update();
        jdbc.sql("DELETE FROM jobrunr_jobs").update();
    }

    @AfterAll
    static void nothing() {
        // the application context (and with it the background server and dashboard) is closed by the test framework
    }

    private static byte[] fixture() {
        try (InputStream in = ManualTriggerTest.class.getResourceAsStream("/fixtures/zero-wire-feed.xml")) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FetchResult.Fetched fetched() {
        return new FetchResult.Fetched(fixture(), "\"e\"", "Thu, 05 Nov 2026 00:00:00 GMT",
                URI.create("https://radar.cloudflare.com/security/zero-wire/rss"));
    }

    private static HttpResponse<String> post(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + DASHBOARD_PORT + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private List<IngestionRun> awaitRuns(int count) {
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(500))
                .until(() -> runs.latest(SOURCE, 20).size() >= count);
        return runs.latest(SOURCE, 20);
    }

    // ------------------------------------------------------------------ the dashboard's "Trigger now"

    @Test
    void triggerNowOnTheDashboardRunsTheIngestionAndRecordsARunLikeAScheduledOne() throws Exception {
        scheduling.registerRecurringJob();
        feed.returns(fetched());

        HttpResponse<String> response = post("/api/recurring-jobs/" + IngestionScheduling.RECURRING_JOB_ID
                + "/trigger", AUTH);

        assertThat(response.statusCode()).isBetween(200, 299);
        IngestionRun run = awaitRuns(1).get(0);
        assertThat(run.trigger()).isEqualTo(IngestionTrigger.SCHEDULED);
        assertThat(run.outcome()).isEqualTo(IngestionOutcome.SUCCESS);
        assertThat(run.itemsSeen()).isEqualTo(8);
        assertThat(run.itemsNew()).isEqualTo(8);
        assertThat(run.errorReason()).isNull();
        assertThat(run.startedAt()).isNotNull().isBeforeOrEqualTo(run.finishedAt());
        assertThat(vulnerabilities.count()).isEqualTo(8);
        await().atMost(PATIENCE).until(() -> storage.countJobs(StateName.SUCCEEDED) >= 1);
    }

    @Test
    void triggerNowWithoutCredentialsIsRejectedAndRunsNothing() throws Exception {
        scheduling.registerRecurringJob();
        feed.returns(fetched());

        HttpResponse<String> response = post("/api/recurring-jobs/" + IngestionScheduling.RECURRING_JOB_ID
                + "/trigger", null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(storage.countJobs(StateName.ENQUEUED)).isZero();
        assertThat(runs.latest(SOURCE, 5)).isEmpty();
        assertThat(feed.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ the scheduler

    @Test
    void aManualJobEnqueuedThroughTheSchedulerRecordsAManualRunOfTheSameShape() {
        feed.returns(fetched());

        scheduler.<IngestionJobs>enqueue(jobs -> jobs.manualIngestion(JobContext.Null));

        IngestionRun run = awaitRuns(1).get(0);
        assertThat(run.trigger()).isEqualTo(IngestionTrigger.MANUAL);
        assertThat(run.outcome()).isEqualTo(IngestionOutcome.SUCCESS);
        assertThat(run.itemsSeen()).isEqualTo(8);
        assertThat(run.errorReason()).isNull();
        assertThat(run.startedAt()).isNotNull().isBeforeOrEqualTo(run.finishedAt());
    }

    @Test
    void theJobsOwnDashboardLogSaysWhatTheRunProcessed() {
        feed.returns(fetched());

        JobId id = scheduler.<IngestionJobs>enqueue(jobs -> jobs.manualIngestion(JobContext.Null));

        IngestionRun run = awaitRuns(1).get(0);
        await().atMost(PATIENCE).until(() -> storage.getJobById(id).getState() == StateName.SUCCEEDED);
        assertThat(dashboardLog(id)).containsExactly(
                "Run " + run.id() + ": SUCCESS - seen 8, new 8, updated 0, unchanged 0, skipped 0");
    }

    @Test
    void aFailedJobsDashboardLogCarriesTheFailureReason() {
        feed.fails(new FetchFailedException("HTTP 503 from 'radar.cloudflare.com'"));

        JobId id = scheduler.<IngestionJobs>enqueue(jobs -> jobs.manualIngestion(JobContext.Null));

        IngestionRun run = awaitRuns(1).get(0);
        await().atMost(PATIENCE).until(() -> storage.getJobById(id).getState() == StateName.FAILED);
        assertThat(dashboardLog(id)).containsExactly(
                "Run " + run.id() + ": FAILED - failed: HTTP 503 from 'radar.cloudflare.com'");
    }

    /** The messages written with {@code jobContext.logger()}, as stored with the job and shown by the dashboard. */
    private List<String> dashboardLog(JobId id) {
        return storage.getJobById(id).getMetadata().values().stream()
                .filter(JobDashboardLogger.JobDashboardLogLines.class::isInstance)
                .map(JobDashboardLogger.JobDashboardLogLines.class::cast)
                .flatMap(lines -> lines.getLogLines().stream())
                .map(JobDashboardLogger.JobDashboardLogLine::getLogMessage)
                .toList();
    }

    // ------------------------------------------------------------------ failures and retries

    @Test
    void aFailedRunLeavesTheJobFailedWhenItHasNoRetries() {
        feed.fails(new FetchFailedException("HTTP 500 from 'radar.cloudflare.com'"));

        scheduler.<IngestionJobs>enqueue(jobs -> jobs.manualIngestion(JobContext.Null));

        IngestionRun run = awaitRuns(1).get(0);
        assertThat(run.outcome()).isEqualTo(IngestionOutcome.FAILED);
        assertThat(run.errorReason()).isEqualTo("failed: HTTP 500 from 'radar.cloudflare.com'");
        await().atMost(PATIENCE).until(() -> storage.countJobs(StateName.FAILED) >= 1);
        assertThat(vulnerabilities.count()).isZero();
    }

    @Test
    void aFailedRunIsRetriedByJobRunrAndTheRetrySucceeds() {
        feed.fails(new FetchFailedException("timed out after PT30S fetching from 'radar.cloudflare.com'"));
        feed.returns(fetched());

        scheduler.<IngestionJobs>enqueue(jobs -> jobs.catchUpIngestion(JobContext.Null));

        List<IngestionRun> history = awaitRuns(2);
        assertThat(history).extracting(IngestionRun::outcome)
                .containsExactlyInAnyOrder(IngestionOutcome.FAILED, IngestionOutcome.SUCCESS);
        assertThat(history).extracting(IngestionRun::trigger).containsOnly(IngestionTrigger.CATCH_UP);
        await().atMost(PATIENCE).until(() -> storage.countJobs(StateName.SUCCEEDED) >= 1);
        assertThat(storage.countJobs(StateName.FAILED)).as("the retried job ended succeeded").isZero();
        assertThat(vulnerabilities.count()).isEqualTo(8);
    }
}
