package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** What the application does by itself when it starts on an empty database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:startup;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false"
})
class IngestionStartupTest {

    @Autowired
    StorageProvider storage;

    @Test
    void startingOnAnEmptyDatabaseRegistersTheDailyJobAndEnqueuesOneCatchUp() {
        assertThat(storage.getRecurringJobs()).extracting(job -> job.getId())
                .containsExactly(IngestionScheduling.RECURRING_JOB_ID);
        assertThat(storage.getRecurringJobs().get(0).getScheduleExpression()).isEqualTo("0 3 * * *");
        assertThat(storage.getRecurringJobs().get(0).getZoneId()).isEqualTo("UTC");
        assertThat(storage.countJobs(StateName.ENQUEUED)).isEqualTo(1);
    }
}
