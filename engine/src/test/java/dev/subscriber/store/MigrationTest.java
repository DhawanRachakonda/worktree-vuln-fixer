package dev.subscriber.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationTest {

    @TempDir
    Path dir;

    private static Flyway flyway(DataSource ds) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
    }

    private static DataSource h2(String url) {
        return new DriverManagerDataSource(url, "sa", "");
    }

    private static List<String> tables(DataSource ds) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = ds.getConnection(); ResultSet rs = c.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME").toLowerCase());
            }
        }
        return names;
    }

    @Test
    void theMigrationAppliesToAFreshInMemoryDatabase() throws Exception {
        DataSource ds = h2("jdbc:h2:mem:migration-fresh;DB_CLOSE_DELAY=-1");
        assertThat(flyway(ds).migrate().migrationsExecuted).isEqualTo(2);
        assertThat(tables(ds)).contains("fix_session", "fix_session_event", "fix_session_state");
    }

    @Test
    void theMigrationAppliesToAFreshFileDatabaseAndReapplyingChangesNothing() throws Exception {
        DataSource ds = h2("jdbc:h2:file:" + dir.toString().replace('\\', '/') + "/migration");
        assertThat(flyway(ds).migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway(ds).migrate().migrationsExecuted).isZero();
        assertThat(tables(ds)).contains("fix_session", "fix_session_event", "fix_session_state");
    }

    @Test
    void aSessionRecordedBeforeV2KeepsWorkingAndHasEmptyWorktreeFields() throws Exception {
        DataSource ds = h2("jdbc:h2:file:" + dir.toString().replace('\\', '/') + "/upgrade");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("1").load().migrate();
        try (Connection c = ds.getConnection(); var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO fix_session (id, state, subject, source, report_path, agent, engine, received_at) VALUES "
                    + "('aaaaaaaa-0000-0000-0000-000000000000', 'DONE', 's', 'Security audit', 'r', 'a', 'copilot', CURRENT_TIMESTAMP)");
        }

        assertThat(flyway(ds).migrate().migrationsExecuted).isEqualTo(1);

        try (Connection c = ds.getConnection(); var st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT work_name, work_path, work_branch, base_commit, fix_commits, state FROM fix_session")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("state")).isEqualTo("DONE");
            for (String column : List.of("work_name", "work_path", "work_branch", "base_commit", "fix_commits")) {
                assertThat(rs.getObject(column)).as(column).isNull();
            }
        }
    }
}
