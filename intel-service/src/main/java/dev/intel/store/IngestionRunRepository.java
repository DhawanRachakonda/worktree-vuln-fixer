package dev.intel.store;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRunRepository {

    private final JdbcClient jdbc;

    public IngestionRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(IngestionRun r) {
        jdbc.sql("""
                INSERT INTO ingestion_run (id, source_name, trigger_kind, started_at, finished_at, outcome,
                    items_seen, items_new, items_updated, items_unchanged, items_skipped, error_reason)
                VALUES (:id, :source, :trigger, :started, :finished, :outcome, :seen, :new, :updated, :unchanged,
                    :skipped, :error)""")
                .param("id", r.id())
                .param("source", r.sourceName())
                .param("trigger", r.trigger().name())
                .param("started", VulnerabilityRepository.ts(VulnerabilityRepository.micros(r.startedAt())))
                .param("finished", VulnerabilityRepository.ts(VulnerabilityRepository.micros(r.finishedAt())))
                .param("outcome", r.outcome().name())
                .param("seen", r.itemsSeen())
                .param("new", r.itemsNew())
                .param("updated", r.itemsUpdated())
                .param("unchanged", r.itemsUnchanged())
                .param("skipped", r.itemsSkipped())
                .param("error", r.errorReason())
                .update();
    }

    /** Finish time of the latest run that fetched the feed successfully, or empty when none ever did. */
    public Optional<Instant> lastSuccessfulIngestion(String sourceName) {
        // MAX() over no rows yields one row holding NULL, which is the "never succeeded" answer.
        List<OffsetDateTime> rows = jdbc.sql("SELECT MAX(finished_at) FROM ingestion_run"
                        + " WHERE source_name = :source AND outcome IN ('SUCCESS', 'NO_CHANGE')")
                .param("source", sourceName)
                .query((rs, n) -> rs.getObject(1, OffsetDateTime.class))
                .list();
        OffsetDateTime max = rows.isEmpty() ? null : rows.get(0);
        return Optional.ofNullable(max).map(OffsetDateTime::toInstant);
    }

    /** The most recent runs, newest first. */
    public List<IngestionRun> latest(String sourceName, int limit) {
        return jdbc.sql("""
                SELECT id, source_name, trigger_kind, started_at, finished_at, outcome, items_seen, items_new,
                    items_updated, items_unchanged, items_skipped, error_reason
                FROM ingestion_run WHERE source_name = :source
                ORDER BY finished_at DESC, id DESC FETCH FIRST :limit ROWS ONLY""")
                .param("source", sourceName)
                .param("limit", limit)
                .query((rs, n) -> new IngestionRun(rs.getString("id"), rs.getString("source_name"),
                        IngestionTrigger.valueOf(rs.getString("trigger_kind")),
                        VulnerabilityRepository.instant(rs, "started_at"),
                        VulnerabilityRepository.instant(rs, "finished_at"),
                        IngestionOutcome.valueOf(rs.getString("outcome")), rs.getInt("items_seen"),
                        rs.getInt("items_new"), rs.getInt("items_updated"), rs.getInt("items_unchanged"),
                        rs.getInt("items_skipped"), rs.getString("error_reason")))
                .list();
    }
}
