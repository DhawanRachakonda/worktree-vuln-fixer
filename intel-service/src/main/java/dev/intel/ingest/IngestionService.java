package dev.intel.ingest;

import dev.intel.config.IntelProperties;
import dev.intel.feed.FeedDocumentReader;
import dev.intel.feed.FeedEntry;
import dev.intel.feed.FeedItemNormalizer;
import dev.intel.feed.FeedSource;
import dev.intel.feed.FetchException;
import dev.intel.feed.FetchResult;
import dev.intel.feed.MalformedItemException;
import dev.intel.store.FeedSourceState;
import dev.intel.store.FeedSourceStateRepository;
import dev.intel.store.IngestionOutcome;
import dev.intel.store.IngestionRun;
import dev.intel.store.IngestionRunRepository;
import dev.intel.store.IngestionTrigger;
import dev.intel.store.VulnerabilityDraft;
import dev.intel.store.VulnerabilityRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one ingestion of the feed: fetch, parse and normalize everything first, then write every change in a single
 * transaction together with the source state and the run record. A run that fails leaves previously stored records
 * exactly as they were, and the failure is recorded as its own run.
 *
 * <p>Fetch problems are returned as a recorded run with outcome {@code FAILED} or {@code REFUSED}; only unexpected
 * errors (for example a database failure) are also rethrown, after being recorded on a best-effort basis.
 */
@Service
public class IngestionService {

    /** The one feed source this service ingests. */
    public static final String SOURCE_NAME = "zero-wire";

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final FeedSource source;
    private final FeedDocumentReader reader;
    private final FeedItemNormalizer normalizer;
    private final VulnerabilityRepository vulnerabilities;
    private final FeedSourceStateRepository states;
    private final IngestionRunRepository runs;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final URI feedUri;
    private final IngestionMetrics metrics;

    public IngestionService(FeedSource source, FeedDocumentReader reader, FeedItemNormalizer normalizer,
            VulnerabilityRepository vulnerabilities, FeedSourceStateRepository states, IngestionRunRepository runs,
            PlatformTransactionManager transactionManager, Clock clock, IntelProperties properties,
            IngestionMetrics metrics) {
        this.metrics = metrics;
        this.source = source;
        this.reader = reader;
        this.normalizer = normalizer;
        this.vulnerabilities = vulnerabilities;
        this.states = states;
        this.runs = runs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.feedUri = URI.create(properties.feed().url());
    }

    public IngestionRun run(IngestionTrigger trigger) {
        Instant started = clock.instant();
        String runId = UUID.randomUUID().toString();
        FeedSourceState prior = states.find(SOURCE_NAME)
                .orElse(new FeedSourceState(SOURCE_NAME, null, null, null, null));
        try {
            FetchResult result = source.fetch(feedUri, prior.etag(), prior.lastModified());
            IngestionRun run = switch (result) {
                case FetchResult.NotModified notModified -> recordNotModified(runId, trigger, started, prior);
                case FetchResult.Fetched fetched -> ingest(runId, trigger, started, fetched);
            };
            metrics.record(run);
            log.info("Ingestion run {} ({}): {} - seen {}, new {}, updated {}, unchanged {}, skipped {}", run.id(),
                    trigger, run.outcome(), run.itemsSeen(), run.itemsNew(), run.itemsUpdated(),
                    run.itemsUnchanged(), run.itemsSkipped());
            return run;
        } catch (FetchException e) {
            IngestionRun run = recordFailure(runId, trigger, started, prior, e.outcome(), e.reason());
            log.warn("Ingestion run {} ({}): {} - {}", runId, trigger, run.outcome(), e.reason());
            return run;
        } catch (RuntimeException e) {
            log.error("Ingestion run {} ({}) failed unexpectedly", runId, trigger, e);
            try {
                recordFailure(runId, trigger, started, prior, IngestionOutcome.FAILED,
                        "failed: unexpected error: " + e.getClass().getSimpleName());
            } catch (RuntimeException secondary) {
                e.addSuppressed(secondary);
            }
            throw e;
        }
    }

    private IngestionRun ingest(String runId, IngestionTrigger trigger, Instant started, FetchResult.Fetched fetched)
            throws FetchException {
        List<FeedEntry> entries = reader.read(fetched.body());
        List<VulnerabilityDraft> drafts = new ArrayList<>(entries.size());
        int skipped = 0;
        int position = 0;
        for (FeedEntry entry : entries) {
            position++;
            try {
                drafts.add(normalizer.normalize(SOURCE_NAME, entry));
            } catch (MalformedItemException e) {
                skipped++;
                // Only the position: nothing the feed said about the item is written to the log.
                log.warn("Ingestion run {}: skipping malformed feed item #{} ({})", runId, position, e.reason());
            }
        }

        int total = entries.size();
        int skippedCount = skipped;
        return Objects.requireNonNull(transaction.execute(status -> {
            Instant now = clock.instant();
            int created = 0;
            int updated = 0;
            int unchanged = 0;
            for (VulnerabilityDraft draft : drafts) {
                switch (vulnerabilities.upsert(draft, now)) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
            }
            Instant finished = clock.instant();
            IngestionOutcome outcome = created + updated > 0 ? IngestionOutcome.SUCCESS : IngestionOutcome.NO_CHANGE;
            IngestionRun run = new IngestionRun(runId, SOURCE_NAME, trigger, started, finished, outcome, total,
                    created, updated, unchanged, skippedCount, null);
            states.save(new FeedSourceState(SOURCE_NAME, fetched.etag(), fetched.lastModified(), finished, null));
            runs.insert(run);
            return run;
        }));
    }

    private IngestionRun recordNotModified(String runId, IngestionTrigger trigger, Instant started,
            FeedSourceState prior) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Instant finished = clock.instant();
            IngestionRun run = new IngestionRun(runId, SOURCE_NAME, trigger, started, finished,
                    IngestionOutcome.NO_CHANGE, 0, 0, 0, 0, 0, null);
            states.save(new FeedSourceState(SOURCE_NAME, prior.etag(), prior.lastModified(), finished, null));
            runs.insert(run);
            return run;
        }));
    }

    /** Records a failed or refused run and keeps the previous validators, in a transaction of its own. */
    private IngestionRun recordFailure(String runId, IngestionTrigger trigger, Instant started, FeedSourceState prior,
            IngestionOutcome outcome, String reason) {
        IngestionRun recorded = Objects.requireNonNull(transaction.execute(status -> {
            Instant finished = clock.instant();
            IngestionRun run = new IngestionRun(runId, SOURCE_NAME, trigger, started, finished, outcome, 0, 0, 0, 0,
                    0, reason);
            states.save(new FeedSourceState(SOURCE_NAME, prior.etag(), prior.lastModified(), finished, reason));
            runs.insert(run);
            return run;
        }));
        metrics.record(recorded);
        return recorded;
    }
}
