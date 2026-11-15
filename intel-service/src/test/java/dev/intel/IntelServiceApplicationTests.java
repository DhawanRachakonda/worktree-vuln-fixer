package dev.intel;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:context-load;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=true",
        "intel.schedule.enabled=false"
})
class IntelServiceApplicationTests {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsOnH2WithJobRunrTables() {
        String product = jdbc.queryForObject("SELECT H2VERSION()", String.class);
        assertThat(product).startsWith("2.");

        Integer jobTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) LIKE 'jobrunr_%'",
                Integer.class);
        assertThat(jobTables).isGreaterThan(0);
    }
}
