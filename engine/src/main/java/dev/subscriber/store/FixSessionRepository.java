package dev.subscriber.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sessions and their events. State changes are explicit methods, and each one is guarded in SQL: a session that is already in a
 * final state is never changed again, whatever calls the repository.
 */
@Repository
public class FixSessionRepository {

    private static final int MAX_REPORT = 1024;
    private static final int MAX_REASON = 2000;

    private final JdbcClient jdbc;

    public FixSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A message that was accepted ({@code QUEUED}) or refused ({@code REJECTED}). */
    public record NewSession(String id, SessionState state, String subject, String source, String report,
            String repositoryPath, String agent, String engine, Instant receivedAt, String reason) {
    }

    public void insert(NewSession s) {
        jdbc.sql("""
                INSERT INTO fix_session (id, state, subject, source, report_path, repository_path, agent, engine, received_at,
                                         reason)
                VALUES (:id, :state, :subject, :source, :report, :repository, :agent, :engine, :receivedAt, :reason)""")
                .param("id", s.id())
                .param("state", s.state().name())
                .param("subject", s.subject())
                .param("source", s.source())
                .param("report", cut(s.report(), MAX_REPORT))
                .param("repository", s.repositoryPath())
                .param("agent", s.agent())
                .param("engine", s.engine())
                .param("receivedAt", offset(s.receivedAt()))
                .param("reason", cut(s.reason(), MAX_REASON))
                .update();
    }

    public void setSnapshot(String id, String snapshotPath) {
        jdbc.sql("UPDATE fix_session SET snapshot_path = :path WHERE id = :id")
                .param("path", snapshotPath).param("id", id).update();
    }

    /** {@code QUEUED} to {@code IN_PROGRESS}. Returns false when the session was not queued. */
    public boolean markStarted(String id, String runner, Instant startedAt, Long pid, Instant pidStartedAt, String headStart,
            Boolean dirtyAtStart) {
        return jdbc.sql("""
                UPDATE fix_session SET state = 'IN_PROGRESS', runner = :runner, started_at = :startedAt, pid = :pid,
                       pid_started_at = :pidStartedAt, head_start = :headStart, dirty_at_start = :dirty
                WHERE id = :id AND state = 'QUEUED'""")
                .param("runner", runner)
                .param("startedAt", offset(startedAt))
                .param("pid", pid)
                .param("pidStartedAt", offset(pidStartedAt))
                .param("headStart", headStart)
                .param("dirty", dirtyAtStart)
                .param("id", id)
                .update() == 1;
    }

    /** Records the worktree of a session that is still queued, at the moment it is created. Returns false when it is not queued. */
    public boolean setWorktree(String id, Worktree worktree) {
        return jdbc.sql("""
                UPDATE fix_session SET work_name = :name, work_path = :path, work_branch = :branch, base_commit = :base
                WHERE id = :id AND state = 'QUEUED'""")
                .param("name", worktree.name())
                .param("path", worktree.path())
                .param("branch", worktree.branch())
                .param("base", worktree.baseCommit())
                .param("id", id)
                .update() == 1;
    }

    /** Records the process of a running session again, for example after the window was re-attached. */
    public void setProcess(String id, Long pid, Instant pidStartedAt) {
        jdbc.sql("UPDATE fix_session SET pid = :pid, pid_started_at = :pidStartedAt WHERE id = :id AND state = 'IN_PROGRESS'")
                .param("pid", pid).param("pidStartedAt", offset(pidStartedAt)).param("id", id).update();
    }

    /**
     * Moves a session that is not final to a final state. Returns false (and changes nothing) when it was already final.
     */
    public boolean markFinal(String id, SessionState state, Instant endedAt, Integer exitCode, String headEnd, String outcome,
            String reason) {
        return markFinal(id, state, endedAt, exitCode, headEnd, outcome, reason, null);
    }

    /** As above, also recording how many commits the agent made on the session's branch (null when there is no worktree). */
    public boolean markFinal(String id, SessionState state, Instant endedAt, Integer exitCode, String headEnd, String outcome,
            String reason, Integer fixCommits) {
        if (!state.isFinal()) {
            throw new IllegalArgumentException("%s is not a final state".formatted(state));
        }
        return jdbc.sql("""
                UPDATE fix_session SET state = :state, ended_at = :endedAt, exit_code = :exitCode, head_end = :headEnd,
                       outcome = :outcome, reason = :reason, fix_commits = :fixCommits
                WHERE id = :id AND state IN ('QUEUED', 'IN_PROGRESS')""")
                .param("state", state.name())
                .param("endedAt", offset(endedAt))
                .param("exitCode", exitCode)
                .param("headEnd", headEnd)
                .param("outcome", outcome)
                .param("reason", cut(reason, MAX_REASON))
                .param("fixCommits", fixCommits)
                .param("id", id)
                .update() == 1;
    }

    public Optional<FixSession> find(String id) {
        return jdbc.sql("SELECT * FROM fix_session WHERE id = :id").param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    /** Newest first; {@code state} may be null for all. */
    public List<FixSession> list(SessionState state, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM fix_session WHERE (:state IS NULL OR state = :state)
                ORDER BY seq DESC OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY""")
                .param("state", state == null ? null : state.name())
                .param("offset", offset)
                .param("limit", limit)
                .query((rs, n) -> map(rs)).list();
    }

    public long count(SessionState state) {
        return jdbc.sql("SELECT COUNT(*) FROM fix_session WHERE (:state IS NULL OR state = :state)")
                .param("state", state == null ? null : state.name())
                .query(Long.class).single();
    }

    /** Queued sessions in arrival order. */
    public List<FixSession> queued() {
        return jdbc.sql("SELECT * FROM fix_session WHERE state = 'QUEUED' ORDER BY seq")
                .query((rs, n) -> map(rs)).list();
    }

    public List<FixSession> inProgress() {
        return jdbc.sql("SELECT * FROM fix_session WHERE state = 'IN_PROGRESS' ORDER BY seq")
                .query((rs, n) -> map(rs)).list();
    }

    /** True when a queued or running session already works on this report. */
    public boolean hasActiveForReport(String report) {
        return jdbc.sql("SELECT COUNT(*) FROM fix_session WHERE report_path = :report AND state IN ('QUEUED', 'IN_PROGRESS')")
                .param("report", cut(report, MAX_REPORT)).query(Long.class).single() > 0;
    }

    /** True when a queued or running session works in this repository. */
    public boolean hasActiveForRepository(String repositoryPath) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM fix_session
                WHERE repository_path = :repository AND state IN ('QUEUED', 'IN_PROGRESS')""")
                .param("repository", repositoryPath).query(Long.class).single() > 0;
    }

    public void appendEvent(String sessionId, Instant at, SessionState state, String detail) {
        jdbc.sql("""
                INSERT INTO fix_session_event (session_id, occurred_at, state, detail)
                VALUES (:sessionId, :at, :state, :detail)""")
                .param("sessionId", sessionId)
                .param("at", offset(at))
                .param("state", state.name())
                .param("detail", cut(detail, MAX_REASON))
                .update();
    }

    /** Events of a session in the order they happened. */
    public List<SessionEvent> events(String sessionId) {
        return jdbc.sql("SELECT * FROM fix_session_event WHERE session_id = :id ORDER BY occurred_at, id")
                .param("id", sessionId)
                .query((rs, n) -> new SessionEvent(rs.getLong("id"), rs.getString("session_id"),
                        instant(rs, "occurred_at"), SessionState.valueOf(rs.getString("state")), rs.getString("detail")))
                .list();
    }

    private static FixSession map(ResultSet rs) throws SQLException {
        return new FixSession(
                rs.getString("id"),
                rs.getLong("seq"),
                SessionState.valueOf(rs.getString("state")),
                rs.getString("subject"),
                rs.getString("source"),
                rs.getString("report_path"),
                rs.getString("snapshot_path"),
                rs.getString("repository_path"),
                rs.getString("agent"),
                rs.getString("runner"),
                rs.getString("engine"),
                instant(rs, "received_at"),
                instant(rs, "started_at"),
                instant(rs, "ended_at"),
                rs.getObject("pid", Long.class),
                instant(rs, "pid_started_at"),
                rs.getObject("exit_code", Integer.class),
                rs.getString("head_start"),
                rs.getString("head_end"),
                rs.getObject("dirty_at_start", Boolean.class),
                rs.getString("outcome"),
                rs.getString("reason"),
                rs.getString("work_name"),
                rs.getString("work_path"),
                rs.getString("work_branch"),
                rs.getString("base_commit"),
                rs.getObject("fix_commits", Integer.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String cut(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }
}
