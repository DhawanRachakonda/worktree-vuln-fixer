package dev.intel.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationTest {

    static final List<String> TABLES = List.of("VULNERABILITY", "VULNERABILITY_CVE", "VULNERABILITY_TAG",
            "VULNERABILITY_LINK", "FEED_SOURCE_STATE", "INGESTION_RUN");

    @Test
    void appliesToAFreshInMemoryDatabase() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:migration-mem;DB_CLOSE_DELAY=-1", "sa", "");

        MigrateResult first = flyway(ds).migrate();

        assertThat(first.success).isTrue();
        assertThat(first.migrationsExecuted).isEqualTo(1);
        assertThat(tables(ds)).containsAll(TABLES);
    }

    @Test
    void appliesToAFreshFileDatabaseAndReapplyingChangesNothing(@TempDir Path dir) {
        String url = "jdbc:h2:file:" + dir.resolve("intel").toAbsolutePath().toString().replace('\\', '/');
        DataSource ds = new DriverManagerDataSource(url, "sa", "");

        MigrateResult first = flyway(ds).migrate();
        assertThat(first.migrationsExecuted).isEqualTo(1);
        assertThat(tables(ds)).containsAll(TABLES);

        // A second Flyway instance on the same, now existing, file finds everything applied.
        Flyway again = flyway(new DriverManagerDataSource(url, "sa", ""));
        MigrateResult second = again.migrate();
        assertThat(second.success).isTrue();
        assertThat(second.migrationsExecuted).isZero();
        assertThat(again.info().applied()).hasSize(1);
        assertThat(again.info().pending()).isEmpty();
    }

    @Test
    void toleratesATableThatAnotherComponentCreatedFirst() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:migration-baseline;DB_CLOSE_DELAY=-1", "sa", "");
        new JdbcTemplate(ds).execute("CREATE TABLE jobrunr_like (id INT PRIMARY KEY)");

        MigrateResult result = Flyway.configure().dataSource(ds).baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();

        assertThat(result.success).isTrue();
        assertThat(tables(ds)).containsAll(TABLES);
    }

    static Flyway flyway(DataSource ds) {
        return Flyway.configure().dataSource(ds).load();
    }

    static List<String> tables(DataSource ds) {
        return new JdbcTemplate(ds).queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'", String.class);
    }
}
