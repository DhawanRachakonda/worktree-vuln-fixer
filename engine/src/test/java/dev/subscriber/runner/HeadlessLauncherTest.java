package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.runner.LaunchPlan.Flavor;
import dev.subscriber.session.SessionFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the generated launcher scripts for real, with a stand-in agent instead of the Copilot CLI. */
class HeadlessLauncherTest {

    @TempDir
    Path temp;

    private SubscriberProperties properties(String command) {
        return new SubscriberProperties(temp.resolve("data").toString().replace('\\', '/'), List.of(temp.toString()), List.of(),
                SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot(command, SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
    }

    private HeadlessLauncher launcher(String command, StandInAgent agent, String status, int exitCode) {
        SubscriberProperties props = properties(command);
        return new HeadlessLauncher(props, new SessionFolders(props), new LauncherScripts(), agent.environment(status, exitCode));
    }

    private Path sessionFolder(String id) throws IOException {
        Path folder = new SessionFolders(properties(StandInAgent.COMMAND)).create(id);
        Files.writeString(folder.resolve("prompt.md"), "the task");
        return folder;
    }

    private static void awaitEnd(RunningSession session) throws Exception {
        session.ended().get(90, TimeUnit.SECONDS);
    }

    @Test
    void aHeadlessSessionRunsWithoutAWindowAndRecordsACleanExit() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop");
        String head = StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);

        RunningSession session = launcher(StandInAgent.COMMAND, agent, "succeeded", 0).launch(id, repo, "security-fixer");
        assertThat(session.pid()).isPositive();
        assertThat(session.pidStartedAt()).isNotNull();
        awaitEnd(session);

        ExitRecord exit = ExitRecord.read(folder).orElseThrow();
        assertThat(exit.exitCode()).isZero();
        assertThat(exit.endedAt()).isNotNull();
        assertThat(exit.headEnd()).isEqualTo(head);

        List<String> recorded = agent.recordedLines();
        assertThat(Path.of(recorded.get(0).substring("cwd=".length())).toRealPath()).as("the agent runs in the repository")
                .isEqualTo(repo.toRealPath());
        assertThat(recorded).contains("arg=--agent", "arg=security-fixer", "arg=--session-id", "arg=" + id, "arg=-p",
                "arg=--allow-all-tools", "arg=--no-ask-user", "arg=--share");
        assertThat(recorded).doesNotContain("arg=-i", "arg=--trust-folder");
        if (HeadlessLauncher.isWindows()) {
            assertThat(recorded).as("no terminal: input is a pipe and output goes to the log")
                    .contains("inputRedirected=True", "outputRedirected=True");
        }
        assertThat(recorded.get(recorded.size() - 1)).startsWith("arg=Read and follow ").endsWith("prompt.md");
        assertThat(agent.promptCopy()).as("the agent could read the prompt the command line points to").hasContent("the task");
        assertThat(repo.resolve(".appmod/result.json")).content().contains("succeeded");
        assertThat(folder.resolve("logs/runner.log")).exists();
        assertThat(folder.resolve("run.ps1").toFile().exists() || folder.resolve("run.sh").toFile().exists()).isTrue();
    }

    @Test
    void aNonZeroExitCodeIsRecorded() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);

        awaitEnd(launcher(StandInAgent.COMMAND, agent, null, 7).launch(id, repo, "security-fixer"));

        assertThat(ExitRecord.read(folder).orElseThrow().exitCode()).isEqualTo(7);
        assertThat(repo.resolve(".appmod/result.json")).as("the stand-in wrote no report").doesNotExist();
    }

    @Test
    void aRepositoryWithAHostileFolderNameIsEnteredExactlyAndNothingInItRuns() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("r&d 100% ^'x (1) é");
        String head = StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);

        awaitEnd(launcher(StandInAgent.COMMAND, agent, "succeeded", 0).launch(id, repo, "security-fixer"));

        ExitRecord exit = ExitRecord.read(folder).orElseThrow();
        assertThat(exit.exitCode()).isZero();
        assertThat(exit.headEnd()).isEqualTo(head);
        assertThat(Path.of(agent.recordedLines().get(0).substring("cwd=".length())).toRealPath()).isEqualTo(repo.toRealPath());
        assertThat(repo.resolve(".appmod/result.json")).exists();
    }

    @Test
    void anAgentCommandThatDoesNotExistStillLeavesAnExitRecord() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);

        awaitEnd(launcher("no-such-agent-command", agent, null, 0).launch(id, repo, "security-fixer"));

        ExitRecord exit = ExitRecord.read(folder).orElseThrow();
        assertThat(exit.exitCode()).as("the agent never ran, so the record says it did not finish normally").isNotZero();
        assertThat(exit.endedAt()).isNotNull();
        assertThat(folder.resolve("logs/runner.log")).content().isNotEmpty();
    }

    @Test
    void aRepositoryThatIsNotAGitRepositoryGivesANullCommitButStillARecord() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = Files.createDirectories(temp.resolve("plain"));
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);

        awaitEnd(launcher(StandInAgent.COMMAND, agent, "succeeded", 0).launch(id, repo, "security-fixer"));

        ExitRecord exit = ExitRecord.read(folder).orElseThrow();
        assertThat(exit.exitCode()).isZero();
        assertThat(exit.headEnd()).isNull();
    }

    @Test
    void theShellScriptHandsTheRulesToTheAgentAsSeparateArgumentsAndNoBlanketFlag() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("rules shop");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);
        Path script = new LauncherScripts().write(new LaunchPlan(id, folder, repo, "security-fixer", StandInAgent.COMMAND,
                SubscriberProperties.PermissionMode.ALLOW_LIST, List.of("shell(git:*)", "write(C:/my work/**)"), List.of("shell(git push:*)"),
                Flavor.HEADLESS_SHELL));

        ProcessBuilder builder = new ProcessBuilder("sh", script.toString()).directory(folder.toFile()).redirectErrorStream(true);
        agent.environment("succeeded", 0).forEach((k, v) -> HeadlessLauncher.setEnvironment(builder.environment(), k, v));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            Assumptions.abort("no sh on this machine");
            return;
        }
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as(output).isZero();
        assertThat(agent.recordedLines()).contains("arg=--allow-tool", "arg=shell(git:*)", "arg=write(C:/my work/**)", "arg=--deny-tool",
                "arg=shell(git push:*)", "arg=--no-ask-user").doesNotContain("arg=--allow-all-tools");
    }

    @Test
    void theShellScriptRunsToo() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("sh shop");
        String head = StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = sessionFolder(id);
        Path script = new LauncherScripts().write(new LaunchPlan(id, folder, repo, "security-fixer", StandInAgent.COMMAND,
                SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS, Flavor.HEADLESS_SHELL));

        ProcessBuilder builder = new ProcessBuilder("sh", script.toString()).directory(folder.toFile()).redirectErrorStream(true);
        agent.environment("partially_succeeded", 3).forEach((k, v) -> HeadlessLauncher.setEnvironment(builder.environment(), k, v));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            Assumptions.abort("no sh on this machine");
            return;
        }
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(90, TimeUnit.SECONDS)).isTrue();

        assertThat(process.exitValue()).as(output).isEqualTo(3);
        ExitRecord exit = ExitRecord.read(folder).orElseThrow();
        assertThat(exit.exitCode()).as(output).isEqualTo(3);
        assertThat(exit.headEnd()).isEqualTo(head);
        assertThat(repo.resolve(".appmod/result.json")).content().contains("partially_succeeded");
        assertThat(agent.recordedLines()).contains("arg=-p", "arg=--no-ask-user", "arg=--session-id", "arg=" + id);
    }
}
