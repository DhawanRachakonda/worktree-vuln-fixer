package dev.intel.store;

import static dev.intel.store.TestDrafts.SOURCE;
import static dev.intel.store.TestDrafts.T0;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import({IngestionRunRepository.class, FeedSourceStateRepository.class})
class IngestionRepositoriesTest {

    @Autowired
    IngestionRunRepository runs;

    @Autowired
    FeedSourceStateRepository states;

    private static IngestionRun run(IngestionOutcome outcome, Instant finished, String error) {
        return new IngestionRun(UUID.randomUUID().toString(), SOURCE, IngestionTrigger.SCHEDULED,
                finished.minusSeconds(5), finished, outcome, 8, 2, 1, 4, 1, error);
    }

    @Test
    void neverIngestedHasNoSuccessfulRun() {
        assertThat(runs.lastSuccessfulIngestion(SOURCE)).isEmpty();
        assertThat(runs.latest(SOURCE, 10)).isEmpty();
    }

    @Test
    void successIsReportedAsTheLastSuccessfulIngestion() {
        Instant finished = T0.plus(Duration.ofHours(1));
        runs.insert(run(IngestionOutcome.SUCCESS, finished, null));

        assertThat(runs.lastSuccessfulIngestion(SOURCE)).contains(finished);
    }

    @Test
    void aFailureAfterASuccessDoesNotMoveTheLastSuccess() {
        Instant success = T0.plus(Duration.ofHours(1));
        runs.insert(run(IngestionOutcome.SUCCESS, success, null));
        runs.insert(run(IngestionOutcome.FAILED, success.plus(Duration.ofDays(1)), "timeout"));
        runs.insert(run(IngestionOutcome.REFUSED, success.plus(Duration.ofDays(2)), "not https"));

        assertThat(runs.lastSuccessfulIngestion(SOURCE)).contains(success);
        assertThat(runs.latest(SOURCE, 10)).extracting(IngestionRun::outcome)
                .containsExactly(IngestionOutcome.REFUSED, IngestionOutcome.FAILED, IngestionOutcome.SUCCESS);
    }

    @Test
    void anUnchangedFeedCountsAsSuccessful() {
        Instant first = T0.plus(Duration.ofHours(1));
        Instant second = first.plus(Duration.ofDays(1));
        runs.insert(run(IngestionOutcome.SUCCESS, first, null));
        runs.insert(run(IngestionOutcome.NO_CHANGE, second, null));

        assertThat(runs.lastSuccessfulIngestion(SOURCE)).contains(second);
    }

    @Test
    void failuresOnlyMeansNeverSucceeded() {
        runs.insert(run(IngestionOutcome.FAILED, T0, "boom"));

        assertThat(runs.lastSuccessfulIngestion(SOURCE)).isEmpty();
        IngestionRun stored = runs.latest(SOURCE, 1).get(0);
        assertThat(stored.errorReason()).isEqualTo("boom");
        assertThat(stored.itemsSeen()).isEqualTo(8);
        assertThat(stored.itemsNew()).isEqualTo(2);
        assertThat(stored.itemsUpdated()).isEqualTo(1);
        assertThat(stored.itemsUnchanged()).isEqualTo(4);
        assertThat(stored.itemsSkipped()).isEqualTo(1);
        assertThat(stored.trigger()).isEqualTo(IngestionTrigger.SCHEDULED);
    }

    @Test
    void latestReturnsNewestFirstAndRespectsTheLimit() {
        for (int i = 0; i < 5; i++) {
            runs.insert(run(IngestionOutcome.SUCCESS, T0.plus(Duration.ofDays(i)), null));
        }

        assertThat(runs.latest(SOURCE, 3)).extracting(IngestionRun::finishedAt)
                .containsExactly(T0.plus(Duration.ofDays(4)), T0.plus(Duration.ofDays(3)), T0.plus(Duration.ofDays(2)));
    }

    @Test
    void feedSourceStateIsInsertedThenReplaced() {
        assertThat(states.find(SOURCE)).isEmpty();

        states.save(new FeedSourceState(SOURCE, "\"etag-1\"", "Thu, 05 Nov 2026 00:00:00 GMT", T0, null));
        assertThat(states.find(SOURCE)).contains(
                new FeedSourceState(SOURCE, "\"etag-1\"", "Thu, 05 Nov 2026 00:00:00 GMT", T0, null));

        Instant later = T0.plus(Duration.ofDays(1));
        states.save(new FeedSourceState(SOURCE, null, null, later, "fetch failed: timeout"));
        assertThat(states.find(SOURCE)).contains(new FeedSourceState(SOURCE, null, null, later, "fetch failed: timeout"));
    }
}
