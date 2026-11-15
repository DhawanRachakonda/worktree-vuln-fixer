package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.session.SessionFolders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opens a real console window for a moment. Opt-in because it needs a desktop session and flashes a window:
 * {@code ./gradlew test -Pwindow-tests}.
 */
class WindowLauncherTest {

    @TempDir
    Path temp;

    @Test
    void aRealWindowOpensShowsTheTitleRunsTheAgentAndClosesWhenItEnds() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("window.tests")), "opt-in: run with -Pwindow-tests");
        Assumptions.assumeTrue(HeadlessLauncher.isWindows(), "windows only");

        // A data directory with a space in its name: the title and the script path both need quoting on the command line.
        SubscriberProperties props = new SubscriberProperties(temp.resolve("appmod data").toString().replace('\\', '/'),
                List.of(temp.toString()), List.of(), SubscriberProperties.Runner.WINDOW, 1,
                new SubscriberProperties.Copilot(StandInAgent.COMMAND, SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        SessionFolders folders = new SessionFolders(props);
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("r&d 100% ^'x (1) é");
        String head = StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = folders.create(id);
        Files.writeString(folder.resolve("prompt.md"), "the task");

        WindowLauncher launcher = new WindowLauncher(props, folders, new LauncherScripts(), agent.environment("succeeded", 0));
        RunningSession session = launcher.launch(id, repo, "security-fixer");

        ProcessToken token = new ProcessToken(session.pid(), session.pidStartedAt());
        assertThat(session.pid()).isPositive();
        session.ended().get(120, TimeUnit.SECONDS);
        assertThat(token.isAlive()).as("the wrapper ended with the window").isFalse();

        List<String> recorded = agent.recordedLines();
        String title = recorded.stream().filter(l -> l.startsWith("windowTitle=")).findFirst().orElseThrow();
        // The original defect: the agent's input was a closed pipe, so nothing could be typed. In a real window both are terminals.
        assertThat(recorded).as("a person can type: input is a terminal, not a pipe").contains("inputRedirected=False");
        assertThat(recorded).as("and sees the output: it is a terminal, not a file").contains("outputRedirected=False");
        assertThat(title).isEqualTo("windowTitle=appmod fix " + id.substring(0, 8));
        assertThat(recorded).contains("arg=-i").doesNotContain("arg=-p", "arg=--no-ask-user", "arg=--trust-folder");
        assertThat(recorded).contains("arg=--session-id", "arg=" + id, "arg=--allow-all-tools");
        assertThat(Path.of(recorded.get(0).substring("cwd=".length())).toRealPath()).isEqualTo(repo.toRealPath());

        ExitRecord exit = ExitRecord.read(folder).orElseThrow();
        assertThat(exit.exitCode()).isZero();
        assertThat(exit.headEnd()).isEqualTo(head);
        assertThat(repo.resolve(".appmod/result.json")).content().contains("succeeded");
    }

    @Test
    void aReopenedSessionOpensARealWindowInTheRepositoryAndRecordsNothing() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("window.tests")), "opt-in: run with -Pwindow-tests");
        Assumptions.assumeTrue(HeadlessLauncher.isWindows(), "windows only");

        SubscriberProperties props = new SubscriberProperties(temp.resolve("appmod data").toString().replace('\\', '/'),
                List.of(temp.toString()), List.of(), SubscriberProperties.Runner.WINDOW, 1,
                new SubscriberProperties.Copilot(StandInAgent.COMMAND, SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        SessionFolders folders = new SessionFolders(props);
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("r&d 100% ^'x (1) é");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = folders.create(id);

        new SessionReopener(props, folders, new LauncherScripts(), agent.environment("succeeded", 0)).reopen(id, repo, "copilot");

        // The engine does not wait for the window, so wait for the stand-in's record to appear and be complete.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        List<String> recorded = List.of();
        while (System.nanoTime() < deadline) {
            if (Files.exists(agent.record())) {
                recorded = agent.recordedLines();
                if (recorded.stream().anyMatch(l -> l.startsWith("outputRedirected="))) {
                    break;
                }
            }
            Thread.sleep(500);
        }

        assertThat(recorded).as("the window ran the agent").isNotEmpty();
        assertThat(recorded).contains("inputRedirected=False", "outputRedirected=False");
        assertThat(recorded).contains("arg=--resume", "arg=" + id, "arg=--allow-all-tools");
        assertThat(recorded).doesNotContain("arg=--session-id", "arg=-i", "arg=-p");
        assertThat(recorded.stream().filter(l -> l.startsWith("windowTitle=")).findFirst().orElseThrow())
                .isEqualTo("windowTitle=appmod fix " + id.substring(0, 8) + " (reopened)");
        assertThat(Path.of(recorded.get(0).substring("cwd=".length())).toRealPath()).isEqualTo(repo.toRealPath());
        Thread.sleep(2000);
        assertThat(folder.resolve("exit.json")).as("a reopened window records nothing").doesNotExist();
    }

    @Test
    void aReopenedSessionOpensARealWindowInItsRealWorktree() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("window.tests")), "opt-in: run with -Pwindow-tests");
        Assumptions.assumeTrue(HeadlessLauncher.isWindows(), "windows only");

        SubscriberProperties props = new SubscriberProperties(temp.resolve("appmod data").toString().replace('\\', '/'),
                List.of(temp.toString()), List.of(), SubscriberProperties.Runner.WINDOW, 1,
                new SubscriberProperties.Copilot(StandInAgent.COMMAND, SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        SessionFolders folders = new SessionFolders(props);
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("r&d 100% ^'x (1) é");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path worktree = temp.resolve("r_d_100____1_-fix-" + id.substring(0, 8));
        StandInAgent.git(repo, "worktree", "add", "-q", "-b", "appmod/fix-" + id.substring(0, 8), worktree.toString(), "HEAD");
        folders.create(id);

        new SessionReopener(props, folders, new LauncherScripts(), agent.environment("succeeded", 0)).reopen(id, worktree, "copilot");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        List<String> recorded = List.of();
        while (System.nanoTime() < deadline) {
            if (Files.exists(agent.record())) {
                recorded = agent.recordedLines();
                if (recorded.stream().anyMatch(l -> l.startsWith("outputRedirected="))) {
                    break;
                }
            }
            Thread.sleep(500);
        }

        assertThat(recorded).as("the window ran the agent").isNotEmpty();
        assertThat(Path.of(recorded.get(0).substring("cwd=".length())).toRealPath()).as("in the worktree, not in the checkout")
                .isEqualTo(worktree.toRealPath());
        assertThat(recorded).contains("arg=--resume", "arg=" + id, "inputRedirected=False", "outputRedirected=False");
        assertThat(StandInAgent.git(worktree, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("appmod/fix-" + id.substring(0, 8));
    }

    private SubscriberProperties claudeProperties() {
        return new SubscriberProperties(temp.resolve("appmod data").toString().replace('\\', '/'),
                List.of(temp.toString()), List.of(), SubscriberProperties.Runner.WINDOW, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_LIST, List.of("Read", "Bash(git *)"),
                        List.of("Bash(curl *)")),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.NONE, SubscriberProperties.AgentTool.CLAUDE,
                new SubscriberProperties.Claude(StandInAgent.COMMAND));
    }

    @Test
    void aRealWindowRunsClaudeCodeInteractivelyAndClosesWhenItEnds() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("window.tests")), "opt-in: run with -Pwindow-tests");
        Assumptions.assumeTrue(HeadlessLauncher.isWindows(), "windows only");

        SubscriberProperties props = claudeProperties();
        SessionFolders folders = new SessionFolders(props);
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("r&d 100% ^'x (1) é");
        String head = StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        Path folder = folders.create(id);
        Files.writeString(folder.resolve("prompt.md"), "the task");

        RunningSession session = new WindowLauncher(props, folders, new LauncherScripts(), agent.environment("succeeded", 0)).launch(id, repo, "security-fixer");
        session.ended().get(120, TimeUnit.SECONDS);

        List<String> recorded = agent.recordedLines();
        assertThat(recorded).as("a person can type: input is a terminal").contains("inputRedirected=False", "outputRedirected=False");
        assertThat(recorded).contains("windowTitle=appmod fix " + id.substring(0, 8), "arg=--agent", "arg=security-fixer", "arg=--session-id", "arg=" + id,
                "arg=--allowedTools", "arg=Read,Bash(git *)", "arg=--disallowedTools", "arg=Bash(curl *)");
        assertThat(recorded).as("interactive: no print mode, no copilot-only arguments").doesNotContain("arg=-p", "arg=-i", "arg=--output-format",
                "arg=--no-ask-user", "arg=--allow-all-tools");
        assertThat(recorded).anyMatch(l -> l.startsWith("arg=Read and follow ") && l.endsWith("prompt.md"));
        assertThat(agent.promptCopy()).hasContent("the task");
        assertThat(ExitRecord.read(folder).orElseThrow().headEnd()).isEqualTo(head);
    }

    @Test
    void aReopenedClaudeCodeSessionResumesWithClaudeCodeEvenWhenTheEngineIsSetToCopilot() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getProperty("window.tests")), "opt-in: run with -Pwindow-tests");
        Assumptions.assumeTrue(HeadlessLauncher.isWindows(), "windows only");

        SubscriberProperties base = claudeProperties();
        // The engine is set to Copilot now, with the stand-in as both commands; the recorded engine of the session says claude.
        SubscriberProperties props = new SubscriberProperties(base.dataDir(), base.allowedRoots(), List.of(), SubscriberProperties.Runner.WINDOW, 1,
                new SubscriberProperties.Copilot("copilot-that-must-not-run", SubscriberProperties.PermissionMode.ASK),
                base.nats(), base.access(), SubscriberProperties.Isolation.NONE, SubscriberProperties.AgentTool.COPILOT,
                new SubscriberProperties.Claude(StandInAgent.COMMAND));
        SessionFolders folders = new SessionFolders(props);
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        folders.create(id);

        new SessionReopener(props, folders, new LauncherScripts(), agent.environment("succeeded", 0)).reopen(id, repo, "claude");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        List<String> recorded = List.of();
        while (System.nanoTime() < deadline) {
            if (Files.exists(agent.record())) {
                recorded = agent.recordedLines();
                if (recorded.stream().anyMatch(l -> l.startsWith("outputRedirected="))) {
                    break;
                }
            }
            Thread.sleep(500);
        }

        assertThat(recorded).as("the window ran Claude Code's command").isNotEmpty();
        assertThat(recorded).contains("arg=--resume", "arg=" + id, "arg=--add-dir").doesNotContain("arg=--log-dir");
        assertThat(Path.of(recorded.get(0).substring("cwd=".length())).toRealPath()).isEqualTo(repo.toRealPath());
    }
}
