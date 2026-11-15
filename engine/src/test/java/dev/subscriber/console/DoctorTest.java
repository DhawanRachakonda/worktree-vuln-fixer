package dev.subscriber.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import io.nats.client.Connection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorTest {

    @TempDir
    Path root;

    Path bin;
    Path data;

    @BeforeEach
    void folders() throws IOException {
        bin = Files.createDirectories(root.resolve("bin"));
        data = root.resolve("data");
        Files.createFile(bin.resolve("copilot"));
        Files.createFile(bin.resolve("powershell.exe"));
    }

    private SubscriberProperties properties(List<String> roots, String dataDir, SubscriberProperties.Nats nats) {
        return new SubscriberProperties(dataDir, roots,
                List.of(new Subject("securityaudit.issuefixesAgent", "Security audit", "appmod-harness", null, "issues.csv")),
                SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK), nats,
                new SubscriberProperties.Access(null));
    }

    private SubscriberProperties.Nats nats() {
        return new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null);
    }

    private Doctor doctor(SubscriberProperties properties, Supplier<Boolean> database, Connection.Status nats, boolean windows) {
        return new Doctor(properties, database, () -> nats, new CommandFinder(bin.toString(), windows, ".EXE"), windows);
    }

    private Doctor healthy() {
        return doctor(properties(List.of(root.toString()), data.toString(), nats()), () -> true, Connection.Status.CONNECTED, true);
    }

    @Test
    void everyCheckPassingGivesOneLineEachAndASummary() {
        List<Doctor.Check> checks = healthy().run();

        assertThat(checks).extracting(Doctor.Check::name)
                .containsExactly("database", "nats", "allowed root", "copilot command", "powershell", "data directory");
        assertThat(checks).allMatch(Doctor.Check::passed);
        String report = healthy().report();
        assertThat(report.lines().count()).isEqualTo(checks.size() + 1);
        assertThat(report).contains("PASS  database").contains("All 6 checks passed.").doesNotContain("FAIL");
    }

    @Test
    void powershellIsOnlyCheckedOnWindows() {
        Doctor linux = doctor(properties(List.of(root.toString()), data.toString(), nats()), () -> true, Connection.Status.CONNECTED, false);

        assertThat(linux.run()).extracting(Doctor.Check::name).doesNotContain("powershell").contains("copilot command");
    }

    @Test
    void aDatabaseThatCannotBeQueriedFails() {
        Doctor doctor = doctor(properties(List.of(root.toString()), data.toString(), nats()), () -> {
            throw new IllegalStateException("jdbc:h2:file:/secret/path user=sa password=hunter2");
        }, Connection.Status.CONNECTED, true);

        Doctor.Check check = doctor.run().get(0);
        assertThat(check.passed()).isFalse();
        assertThat(check.detail()).contains("IllegalStateException").doesNotContain("hunter2").doesNotContain("/secret/path");
        assertThat(doctor.report()).contains("FAIL  database").contains("1 of 6 checks failed.");
    }

    @Test
    void aNatsServerThatIsNotConnectedFailsAndSaysWhatHappensToMessages() {
        for (Connection.Status status : new Connection.Status[] {Connection.Status.DISCONNECTED, Connection.Status.CLOSED,
                Connection.Status.RECONNECTING}) {
            Doctor.Check check = doctor(properties(List.of(root.toString()), data.toString(), nats()), () -> true, status, true).run().get(1);

            assertThat(check.passed()).as(status.name()).isFalse();
            assertThat(check.detail()).contains(status.name().toLowerCase()).contains("nats://localhost:4222").contains("lost");
        }
    }

    @Test
    void anAllowedRootThatDoesNotExistFailsAndEachRootIsOneLine() throws IOException {
        Path other = Files.createDirectories(root.resolve("other"));
        Path missing = Files.createDirectories(root.resolve("missing"));
        Doctor doctor = doctor(properties(List.of(other.toString(), missing.toString()), data.toString(), nats()), () -> true,
                Connection.Status.CONNECTED, true);
        Files.delete(missing); // the folder was there at start and is gone now

        List<Doctor.Check> roots = doctor.run().stream().filter(c -> c.name().equals("allowed root")).toList();
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).passed()).isTrue();
        assertThat(roots.get(1).passed()).isFalse();
        assertThat(roots.get(1).detail()).contains(missing.toString()).contains("does not exist");
    }

    @Test
    void aRootThatIsAFileFails() throws IOException {
        Path file = Files.createDirectories(root.resolve("was-a-folder"));
        Doctor doctor = doctor(properties(List.of(file.toString()), data.toString(), nats()), () -> true, Connection.Status.CONNECTED, true);
        Files.delete(file);
        Files.createFile(file); // now a file under the same name

        assertThat(doctor.run().get(2).passed()).isFalse();
    }

    @Test
    void aCopilotCommandThatIsNotOnThePathFails() throws IOException {
        Files.delete(bin.resolve("copilot"));

        Doctor.Check check = healthy().run().stream().filter(c -> c.name().equals("copilot command")).findFirst().orElseThrow();
        assertThat(check.passed()).isFalse();
        assertThat(check.detail()).contains("copilot was not found on the PATH");
    }

    @Test
    void powershellMissingOnWindowsFails() throws IOException {
        Files.delete(bin.resolve("powershell.exe"));

        Doctor.Check check = healthy().run().stream().filter(c -> c.name().equals("powershell")).findFirst().orElseThrow();
        assertThat(check.passed()).isFalse();
    }

    @Test
    void aDataDirectoryThatCannotBeWrittenFails() throws IOException {
        Path blocker = Files.createFile(root.resolve("not-a-folder"));
        Doctor doctor = doctor(properties(List.of(root.toString()), blocker.resolve("data").toString(), nats()), () -> true,
                Connection.Status.CONNECTED, true);

        Doctor.Check check = doctor.run().get(doctor.run().size() - 1);
        assertThat(check.name()).isEqualTo("data directory");
        assertThat(check.passed()).isFalse();
        assertThat(check.detail()).contains("not writable");
    }

    @Test
    void theDoctorLeavesNothingBehindInTheDataDirectory() throws IOException {
        healthy().run();

        try (var files = Files.list(data)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void noSecretIsPrinted() {
        SubscriberProperties.Nats withToken = new SubscriberProperties.Nats("nats://localhost:4222", "nats-secret-token-value", null, null, null);
        SubscriberProperties properties = new SubscriberProperties(data.toString(), List.of(root.toString()),
                List.of(new Subject("securityaudit.issuefixesAgent", "Security audit", "appmod-harness", null, "issues.csv")),
                SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK), withToken,
                new SubscriberProperties.Access("api-secret-token-value"));

        String report = doctor(properties, () -> true, Connection.Status.DISCONNECTED, true).report();

        assertThat(report).doesNotContain("nats-secret-token-value").doesNotContain("api-secret-token-value");
    }

    @Test
    void theFinderUsesEachExecutableExtensionOnWindowsAndNoneElsewhere() throws IOException {
        Files.createFile(bin.resolve("tool.CMD"));
        Files.createFile(bin.resolve("script.ps1"));

        CommandFinder windows = new CommandFinder(bin + File.pathSeparator + "C:/does/not/exist", true, ".EXE;.CMD");
        CommandFinder other = new CommandFinder(bin.toString(), false, ".EXE;.CMD");

        assertThat(windows.find("tool")).isPresent();
        assertThat(windows.find("script")).isPresent();
        assertThat(windows.find("nothing")).isEmpty();
        assertThat(other.find("tool")).isEmpty();
        assertThat(other.find("copilot")).isPresent();
        assertThat(new CommandFinder(null, true, null).find("copilot")).isEmpty();
    }

    @Test
    void withClaudeCodeSelectedTheClaudeCommandIsTheOneThatIsChecked() throws IOException {
        SubscriberProperties claude = new SubscriberProperties(data.toString(), List.of(root.toString()),
                List.of(new Subject("securityaudit.issuefixesAgent", "Security audit", "appmod-harness", null, "issues.csv")),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                nats(), new SubscriberProperties.Access(null), SubscriberProperties.Isolation.NONE, SubscriberProperties.AgentTool.CLAUDE,
                new SubscriberProperties.Claude("claude"));
        Files.delete(bin.resolve("copilot"));

        assertThat(doctor(claude, () -> true, Connection.Status.CONNECTED, false).run().stream().filter(c -> c.name().equals("claude command"))
                .findFirst().orElseThrow().passed()).as("claude is not on the PATH of this test").isFalse();
        Files.createFile(bin.resolve("claude"));
        List<Doctor.Check> checks = doctor(claude, () -> true, Connection.Status.CONNECTED, false).run();

        assertThat(checks).filteredOn(c -> c.name().equals("claude command")).singleElement().satisfies(c -> assertThat(c.passed()).isTrue());
        assertThat(checks).extracting(Doctor.Check::name).doesNotContain("copilot command");
    }
}
