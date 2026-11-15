package dev.subscriber;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionState;
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

    @TempDir
    Path dataDir;

    @TempDir
    Path root;

    private static String dir(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Settings go in as command-line arguments because those outrank application.yml. No test here may start an agent. */
    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(SubscriberEngineApplication.class)
                .web(WebApplicationType.NONE)
                .sources(NeverLaunchConfig.class)
                .properties("spring.main.allow-bean-definition-overriding=true")
                .run("--subscriber.data-dir=" + dir(dataDir), "--subscriber.allowed-roots=" + dir(root), "--subscriber.copilot.permission-mode=allow-all-tools",
                        "--spring.main.banner-mode=off");
    }

    @Test
    void sessionsAndEventsSurviveARestartOnTheSameDataDirectory() {
        Instant at = Instant.parse("2026-11-10T12:00:00Z");
        try (ConfigurableApplicationContext first = start()) {
            FixSessionRepository repo = first.getBean(FixSessionRepository.class);
            repo.insert(new NewSession("11111111-1111-1111-1111-111111111111", SessionState.QUEUED,
                    "securityaudit.issuefixesAgent", "Security audit", "C:/r/.appmod/security-audit/issues.csv", "C:/r",
                    "appmod-harness", "copilot", at, null));
            repo.appendEvent("11111111-1111-1111-1111-111111111111", at, SessionState.QUEUED, "received");
            // A finished session: the restart must not dispatch it, so the test does not depend on how fast the dispatcher is.
            repo.markFinal("11111111-1111-1111-1111-111111111111", SessionState.DONE, at.plusSeconds(60), 0, "endsha", "succeeded", null);
        }
        assertThat(dataDir.resolve("subscriber.mv.db")).as("database file in the temporary directory").exists();

        try (ConfigurableApplicationContext second = start()) {
            FixSessionRepository repo = second.getBean(FixSessionRepository.class);
            FixSession s = repo.find("11111111-1111-1111-1111-111111111111").orElseThrow();
            assertThat(s.state()).isEqualTo(SessionState.DONE);
            assertThat(s.exitCode()).isZero();
            assertThat(s.outcome()).isEqualTo("succeeded");
            assertThat(s.receivedAt()).isEqualTo(at);
            assertThat(repo.events(s.id())).hasSize(1);
            assertThat(repo.queued()).isEmpty();
        }
    }

    @Test
    void aSecondProcessOnTheSameDataDirectoryFailsFastNamingTheDirectory(@TempDir Path logDir) throws Exception {
        try (ConfigurableApplicationContext running = start()) {
            assertThat(running.isRunning()).isTrue();

            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add("dev.subscriber.SubscriberEngineApplication");
            command.add("--subscriber.data-dir=" + dir(dataDir));
            command.add("--subscriber.allowed-roots=" + dir(root));
            command.add("--subscriber.copilot.permission-mode=allow-all-tools");
            command.add("--server.port=0");
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
                    .contains("The subscriber-engine data directory " + dir(dataDir) + " is already in use by another process")
                    .contains("Only one subscriber-engine can use a data directory at a time")
                    .contains("SUBSCRIBER_DATA_DIR");
        }
    }
}
