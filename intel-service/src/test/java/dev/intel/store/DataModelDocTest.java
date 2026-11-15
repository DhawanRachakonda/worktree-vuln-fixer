package dev.intel.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** docs/data-model.md must describe exactly the tables and columns the migrations create. */
class DataModelDocTest {

    private static final Path DOC = Path.of("docs", "data-model.md");
    private static final Pattern TABLE_HEADING = Pattern.compile("^### `([a-z0-9_]+)`\\s*$");
    private static final Pattern COLUMN_ROW = Pattern.compile("^\\| `([a-z0-9_]+)` \\|.*");

    @Test
    void documentAndMigrationsDescribeTheSameTablesAndColumns() throws IOException {
        Map<String, Set<String>> documented = documentedColumns();
        Map<String, Set<String>> migrated = migratedColumns();

        assertThat(documented.keySet()).as("tables documented vs migrated").isEqualTo(migrated.keySet());
        for (String table : migrated.keySet()) {
            assertThat(documented.get(table)).as("columns of %s: documented vs migrated", table)
                    .isEqualTo(migrated.get(table));
        }
    }

    private static Map<String, Set<String>> documentedColumns() throws IOException {
        Map<String, Set<String>> tables = new TreeMap<>();
        String current = null;
        List<String> lines = Files.readAllLines(DOC);
        for (String line : lines) {
            if (line.startsWith("#")) {
                Matcher heading = TABLE_HEADING.matcher(line);
                current = heading.matches() ? heading.group(1) : null;
                if (current != null) {
                    tables.put(current, new TreeSet<>());
                }
                continue;
            }
            if (current != null) {
                Matcher row = COLUMN_ROW.matcher(line);
                if (row.matches()) {
                    tables.get(current).add(row.group(1));
                }
            }
        }
        return tables;
    }

    private static Map<String, Set<String>> migratedColumns() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:data-model-doc;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(ds).load().migrate();
        Map<String, Set<String>> tables = new TreeMap<>();
        new JdbcTemplate(ds).query(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC'",
                rs -> {
                    String table = rs.getString(1).toLowerCase(Locale.ROOT);
                    if (!table.startsWith("flyway_")) {
                        tables.computeIfAbsent(table, t -> new TreeSet<>())
                                .add(rs.getString(2).toLowerCase(Locale.ROOT));
                    }
                });
        return tables;
    }
}
