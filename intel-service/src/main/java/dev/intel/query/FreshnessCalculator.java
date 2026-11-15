package dev.intel.query;

import dev.intel.config.IntelProperties;
import dev.intel.ingest.IngestionService;
import dev.intel.store.IngestionRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Decides how current the stored data is. The tool's answers and the health indicator both use it, so they can never
 * disagree about what "stale" means: the last successful ingestion is older than the configured threshold, or there
 * never was one.
 */
@Component
public class FreshnessCalculator {

    private final IngestionRunRepository runs;
    private final Clock clock;
    private final Duration staleAfter;

    public FreshnessCalculator(IngestionRunRepository runs, Clock clock, IntelProperties properties) {
        this.runs = runs;
        this.clock = clock;
        this.staleAfter = properties.freshness().staleAfter();
    }

    public Freshness now() {
        return at(clock.instant());
    }

    public Freshness at(Instant now) {
        Optional<Instant> last = runs.lastSuccessfulIngestion(IngestionService.SOURCE_NAME);
        if (last.isEmpty()) {
            return new Freshness(null, true, Freshness.Status.NEVER_INGESTED);
        }
        boolean stale = Duration.between(last.get(), now).compareTo(staleAfter) > 0;
        return new Freshness(last.get(), stale, stale ? Freshness.Status.STALE : Freshness.Status.OK);
    }
}
