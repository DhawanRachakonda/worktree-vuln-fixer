package dev.intel.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.IntelServiceApplication;
import java.nio.file.Path;
import java.time.Instant;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Restarting on the same data directory neither duplicates the schedule nor the catch-up. */
class IngestionRestartTest {

    private static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(T0);
        }
    }

    private static ConfigurableApplicationContext start(Path dataDir, String... extra) {
        String[] base = {"--intel.data-dir=" + dataDir.toAbsolutePath().toString().replace('\\', '/'),
                "--jobrunr.background-job-server.enabled=false", "--spring.main.banner-mode=off"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(IntelServiceApplication.class, FixedClock.class)
                .web(WebApplicationType.NONE).run(args);
    }

    @Test
    void startingTwiceOnTheSameDataDirectoryLeavesOneScheduleAndOneCatchUp(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext first = start(dataDir)) {
            StorageProvider storage = first.getBean(StorageProvider.class);
            assertThat(storage.getRecurringJobs()).hasSize(1);
            assertThat(storage.countJobs(StateName.ENQUEUED)).isEqualTo(1);
        }

        try (ConfigurableApplicationContext second = start(dataDir)) {
            StorageProvider storage = second.getBean(StorageProvider.class);
            assertThat(storage.getRecurringJobs()).as("still exactly one daily schedule").hasSize(1);
            assertThat(storage.countJobs(StateName.ENQUEUED)).as("the same catch-up job, not a second one")
                    .isEqualTo(1);
        }
    }

    @Test
    void aChangedCronOnRestartUpdatesTheExistingScheduleInsteadOfAddingOne(@TempDir Path dataDir) {
        try (ConfigurableApplicationContext first = start(dataDir)) {
            assertThat(first.getBean(StorageProvider.class).getRecurringJobs().get(0).getScheduleExpression())
                    .isEqualTo("0 3 * * *");
        }

        try (ConfigurableApplicationContext second = start(dataDir, "--intel.schedule.cron=15 5 * * *",
                "--intel.schedule.zone=Europe/Berlin")) {
            StorageProvider storage = second.getBean(StorageProvider.class);
            assertThat(storage.getRecurringJobs()).hasSize(1);
            assertThat(storage.getRecurringJobs().get(0).getScheduleExpression()).isEqualTo("15 5 * * *");
            assertThat(storage.getRecurringJobs().get(0).getZoneId()).isEqualTo("Europe/Berlin");
        }
    }
}
