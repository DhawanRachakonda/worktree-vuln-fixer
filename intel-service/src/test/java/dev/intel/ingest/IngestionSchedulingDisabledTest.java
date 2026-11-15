package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:scheduling-disabled;DB_CLOSE_DELAY=-1",
        "jobrunr.background-job-server.enabled=false",
        "intel.schedule.enabled=false"
})
class IngestionSchedulingDisabledTest {

    @Autowired
    StorageProvider storage;

    @Test
    void whenSchedulingIsDisabledNeitherTheDailyJobNorACatchUpIsCreated() {
        assertThat(storage.getRecurringJobs()).isEmpty();
        assertThat(storage.countJobs(StateName.ENQUEUED)).isZero();
        assertThat(storage.countJobs(StateName.SCHEDULED)).isZero();
    }
}
