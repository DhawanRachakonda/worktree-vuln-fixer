package dev.intel;

import static org.assertj.core.api.Assertions.assertThat;

import dev.intel.store.Link;
import dev.intel.store.Sections;
import dev.intel.store.UpsertOutcome;
import dev.intel.store.VulnerabilityDraft;
import dev.intel.store.VulnerabilityRepository;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The H2 data file survives a restart and cannot be opened by two processes at once. */
class DurabilityTest {

    private static final String SHA = "a".repeat(64);

    private static String dir(Path dataDir) {
        return dataDir.toAbsolutePath().toString().replace('\\', '/');
    }

    /**
     * Starts the application on the given data directory. The settings are passed as command-line arguments because
     * those outrank {@code application.yml}; default properties would silently lose to it.
     */
    private static ConfigurableApplicationContext start(Path dataDir) {
        return new SpringApplicationBuilder(IntelServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run("--intel.data-dir=" + dir(dataDir),
                        "--jobrunr.background-job-server.enabled=false",
                        "--intel.schedule.enabled=false",
                        "--spring.main.banner-mode=off");
    }

    @Test
    void recordsSurviveARestartOnTheSameDataDirectory(@TempDir Path dataDir) {
        VulnerabilityDraft draft = new VulnerabilityDraft("zero-wire", "durable-1", "Survives restarts",
                "https://radar.cloudflare.com/security/zero-wire/durable-1", Instant.parse("2026-11-05T00:00:00Z"),
                null, "summary", Sections.empty(), List.of("CVE-2026-1"), List.of("Tag"),
                List.of(new Link("https://example.org/x", "x")), "<item>raw</item>", SHA);

        try (ConfigurableApplicationContext first = start(dataDir)) {
            assertThat(first.getEnvironment().getProperty("intel.data-dir")).isEqualTo(dir(dataDir));
            VulnerabilityRepository repo = first.getBean(VulnerabilityRepository.class);
            assertThat(repo.upsert(draft, Instant.parse("2026-11-09T10:00:00Z"))).isEqualTo(UpsertOutcome.CREATED);
        }
        assertThat(dataDir.resolve("intel.mv.db")).as("database file in the temporary directory").exists();

        try (ConfigurableApplicationContext second = start(dataDir)) {
            VulnerabilityRepository repo = second.getBean(VulnerabilityRepository.class);
            assertThat(repo.count()).isEqualTo(1);
            assertThat(repo.findBySourceItem("zero-wire", "durable-1")).hasValueSatisfying(r -> {
                assertThat(r.title()).isEqualTo("Survives restarts");
                assertThat(r.cveIds()).containsExactly("CVE-2026-1");
            });
            // The data file is not re-migrated or re-seeded by the restart.
            assertThat(repo.upsert(draft, Instant.parse("2026-11-10T10:00:00Z"))).isEqualTo(UpsertOutcome.UNCHANGED);
        }
    }

    @Test
    void aSecondProcessOnTheSameDataFileFailsFastWithAClearMessage(@TempDir Path dataDir, @TempDir Path logDir)
            throws Exception {
        try (ConfigurableApplicationContext running = start(dataDir)) {
            assertThat(running.isRunning()).isTrue();
            assertThat(running.getEnvironment().getProperty("intel.data-dir")).isEqualTo(dir(dataDir));

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("dev.intel.IntelServiceApplication");
            command.add("--intel.data-dir=" + dir(dataDir));
            command.add("--server.port=0");
            command.add("--jobrunr.background-job-server.enabled=false");
            command.add("--intel.schedule.enabled=false");
            command.add("--spring.main.banner-mode=off");

            File log = logDir.resolve("second-instance.log").toFile();
            Process second = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
            boolean exited = second.waitFor(90, TimeUnit.SECONDS);
            if (!exited) {
                second.destroyForcibly();
                second.waitFor(10, TimeUnit.SECONDS);
            }
            String output = Files.readString(log.toPath(), StandardCharsets.UTF_8);

            assertThat(exited).as("second instance must exit on its own; output:%n%s", output).isTrue();
            assertThat(second.exitValue()).as("second instance exit code").isNotZero();
            assertThat(output)
                    .contains("The intel-service data file is already in use by another process")
                    .contains("Only one intel-service instance can open the data file at a time")
                    .contains("INTEL_DATA_DIR");
        }
    }
}
