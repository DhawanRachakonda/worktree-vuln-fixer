package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.config.IntelProperties;
import dev.intel.query.FreshnessCalculator;
import dev.intel.store.IngestionOutcome;
import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionRunRepository;
import dev.intel.store.IngestionTrigger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.util.unit.DataSize;

@JdbcTest
@Import(IngestionRunRepository.class)
class IngestionHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Autowired
    IngestionRunRepository runs;

    private final MutableClock clock = new MutableClock(NOW);
    private IngestionHealthIndicator indicator;

    @BeforeEach
    void build() {
        IntelProperties properties = new IntelProperties("./data",
                new IntelProperties.Feed("https://radar.cloudflare.com/security/zero-wire/rss",
                        List.of("radar.cloudflare.com"), DataSize.ofMegabytes(5), Duration.ofSeconds(10),
                        Duration.ofSeconds(30), 3, "test-agent"),
                new IntelProperties.Schedule("0 3 * * *", "UTC", Duration.ofHours(24), true),
                new IntelProperties.Freshness(Duration.ofHours(36)), new IntelProperties.Access(""));
        indicator = new IngestionHealthIndicator(new FreshnessCalculator(runs, clock, properties), runs);
    }

    private void record(IngestionOutcome outcome, Duration ago, String reason) {
        Instant finished = NOW.minus(ago);
        runs.insert(new IngestionRun(UUID.randomUUID().toString(), IngestionService.SOURCE_NAME,
                IngestionTrigger.SCHEDULED, finished.minusSeconds(4), finished, outcome, 8, outcome.successful() ? 1 : 0,
                0, outcome.successful() ? 7 : 0, 0, reason));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastRun(Health health) {
        return (Map<String, Object>) health.getDetails().get("lastRun");
    }

    @Test
    void freshDataAndASuccessfulLastRunIsUp() {
        record(IngestionOutcome.SUCCESS, Duration.ofHours(1), null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("freshness", "OK").containsEntry("stale", false)
                .containsEntry("lastSuccessfulIngestionAt", NOW.minus(Duration.ofHours(1)).toString());
        assertThat(lastRun(health)).containsEntry("outcome", "SUCCESS").containsEntry("trigger", "SCHEDULED")
                .containsEntry("itemsSeen", 8).containsEntry("itemsNew", 1).containsEntry("itemsUnchanged", 7)
                .doesNotContainKey("reason");
    }

    @Test
    void aFailedRunAfterARecentSuccessIsStillUpBecauseTheDataIsFresh() {
        record(IngestionOutcome.SUCCESS, Duration.ofHours(3), null);
        record(IngestionOutcome.FAILED, Duration.ofHours(1), "failed: HTTP 503 from 'radar.cloudflare.com'");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("lastSuccessfulIngestionAt",
                NOW.minus(Duration.ofHours(3)).toString());
        assertThat(lastRun(health)).containsEntry("outcome", "FAILED")
                .containsEntry("reason", "failed: HTTP 503 from 'radar.cloudflare.com'");
    }

    @Test
    void dataOlderThanTheThresholdIsDown() {
        record(IngestionOutcome.SUCCESS, Duration.ofHours(40), null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("freshness", "STALE").containsEntry("stale", true);
    }

    @Test
    void staleDataWithAFailureOnRecordSaysWhyInTheLastRun() {
        record(IngestionOutcome.SUCCESS, Duration.ofHours(60), null);
        record(IngestionOutcome.FAILED, Duration.ofHours(12), "failed: timed out after PT30S fetching from 'radar.cloudflare.com'");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(lastRun(health)).containsEntry("outcome", "FAILED").containsKey("reason");
    }

    @Test
    void neverIngestedIsDownAndSaysSo() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("freshness", "NEVER_INGESTED").containsEntry("stale", true)
                .containsEntry("lastSuccessfulIngestionAt", "never").containsEntry("lastRun", "none");
    }

    @Test
    void onlyFailuresOnRecordIsDownWithTheLatestFailureShown() {
        record(IngestionOutcome.REFUSED, Duration.ofHours(2), "refused: feed URL must use https: http://x.example/rss");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("freshness", "NEVER_INGESTED");
        assertThat(lastRun(health)).containsEntry("outcome", "REFUSED");
    }

    @Test
    void theThresholdIsExclusive() {
        record(IngestionOutcome.SUCCESS, Duration.ofHours(36), null);
        assertThat(indicator.health().getStatus()).as("exactly 36 hours").isEqualTo(Status.UP);

        clock.set(NOW.plusSeconds(1));
        assertThat(indicator.health().getStatus()).as("36 hours and a second").isEqualTo(Status.DOWN);
    }
}
