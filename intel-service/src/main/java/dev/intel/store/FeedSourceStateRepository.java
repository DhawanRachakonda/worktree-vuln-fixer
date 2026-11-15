package dev.intel.store;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FeedSourceStateRepository {

    private final JdbcClient jdbc;

    public FeedSourceStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<FeedSourceState> find(String sourceName) {
        return jdbc.sql("SELECT source_name, etag, last_modified, last_attempt_at, last_error"
                        + " FROM feed_source_state WHERE source_name = :source")
                .param("source", sourceName)
                .query((rs, n) -> new FeedSourceState(rs.getString("source_name"), rs.getString("etag"),
                        rs.getString("last_modified"), VulnerabilityRepository.instant(rs, "last_attempt_at"),
                        rs.getString("last_error")))
                .optional();
    }

    /** Inserts or replaces the state of the source (update first, insert when no row was touched). */
    @Transactional
    public void save(FeedSourceState s) {
        int updated = jdbc.sql("UPDATE feed_source_state SET etag = :etag, last_modified = :modified,"
                        + " last_attempt_at = :attempt, last_error = :error WHERE source_name = :source")
                .param("source", s.sourceName())
                .param("etag", s.etag())
                .param("modified", s.lastModified())
                .param("attempt", VulnerabilityRepository.ts(VulnerabilityRepository.micros(s.lastAttemptAt())))
                .param("error", s.lastError())
                .update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO feed_source_state (source_name, etag, last_modified, last_attempt_at, last_error)"
                            + " VALUES (:source, :etag, :modified, :attempt, :error)")
                    .param("source", s.sourceName())
                    .param("etag", s.etag())
                    .param("modified", s.lastModified())
                    .param("attempt", VulnerabilityRepository.ts(VulnerabilityRepository.micros(s.lastAttemptAt())))
                    .param("error", s.lastError())
                    .update();
        }
    }
}
