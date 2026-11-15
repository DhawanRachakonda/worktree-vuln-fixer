package dev.intel.ingest;

import dev.intel.store.IngestionOutcome;
import dev.intel.store.IngestionRun;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for what ingestion runs did, exposed through the metrics endpoint:
 *
 * <ul>
 * <li>{@code intel.ingestion.runs} tagged {@code outcome}: runs by {@code SUCCESS}, {@code NO_CHANGE}, {@code FAILED} or
 * {@code REFUSED};</li>
 * <li>{@code intel.ingestion.items} tagged {@code result}: feed items {@code new}, {@code updated}, {@code unchanged} and
 * {@code skipped} (malformed, could not be stored).</li>
 * </ul>
 *
 * All counters are registered at zero when the service starts, so they exist from the first scrape.
 */
@Component
public class IngestionMetrics {

    static final String RUNS = "intel.ingestion.runs";
    static final String ITEMS = "intel.ingestion.items";

    private final MeterRegistry registry;

    public IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (IngestionOutcome outcome : IngestionOutcome.values()) {
            registry.counter(RUNS, "outcome", outcome.name());
        }
        for (String result : new String[] {"new", "updated", "unchanged", "skipped"}) {
            registry.counter(ITEMS, "result", result);
        }
    }

    /** Counts one recorded run. */
    public void record(IngestionRun run) {
        registry.counter(RUNS, "outcome", run.outcome().name()).increment();
        add("new", run.itemsNew());
        add("updated", run.itemsUpdated());
        add("unchanged", run.itemsUnchanged());
        add("skipped", run.itemsSkipped());
    }

    private void add(String result, int count) {
        if (count > 0) {
            registry.counter(ITEMS, "result", result).increment(count);
        }
    }
}
