package dev.intel.ingest;

import dev.intel.query.Freshness;
import dev.intel.query.FreshnessCalculator;
import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionRunRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The {@code ingestion} component of the health endpoint.
 *
 * <p>It is {@code UP} while the stored data is fresh (the last successful ingestion is not older than the staleness
 * threshold), even if a later run failed and is being retried: the service can still answer correctly. It is
 * {@code DOWN} when the data is stale or no ingestion has ever succeeded, which is the condition an operator wants to be
 * alerted about. The details carry the freshness, the time of the last success and the last run with its counts or
 * failure reason; they contain no feed text.
 */
@Component
public class IngestionHealthIndicator implements HealthIndicator {

    private final FreshnessCalculator freshness;
    private final IngestionRunRepository runs;

    public IngestionHealthIndicator(FreshnessCalculator freshness, IngestionRunRepository runs) {
        this.freshness = freshness;
        this.runs = runs;
    }

    @Override
    public Health health() {
        Freshness current = freshness.now();
        Health.Builder health = current.status() == Freshness.Status.OK ? Health.up() : Health.down();
        health.withDetail("freshness", current.status().name());
        health.withDetail("stale", current.stale());
        health.withDetail("lastSuccessfulIngestionAt",
                current.lastSuccessfulIngestionAt() == null ? "never" : current.lastSuccessfulIngestionAt().toString());

        Optional<IngestionRun> last = runs.latest(IngestionService.SOURCE_NAME, 1).stream().findFirst();
        last.ifPresentOrElse(run -> health.withDetail("lastRun", describe(run)),
                () -> health.withDetail("lastRun", "none"));
        return health.build();
    }

    private static Map<String, Object> describe(IngestionRun run) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("id", run.id());
        details.put("trigger", run.trigger().name());
        details.put("outcome", run.outcome().name());
        details.put("finishedAt", run.finishedAt().toString());
        details.put("itemsSeen", run.itemsSeen());
        details.put("itemsNew", run.itemsNew());
        details.put("itemsUpdated", run.itemsUpdated());
        details.put("itemsUnchanged", run.itemsUnchanged());
        details.put("itemsSkipped", run.itemsSkipped());
        if (run.errorReason() != null) {
            details.put("reason", run.errorReason());
        }
        return details;
    }
}
