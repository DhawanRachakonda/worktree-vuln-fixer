package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.AgentTool;
import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.session.SessionFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs the generated Claude Code launcher for real, with a stand-in that records the arguments Claude Code would be given. */
class ClaudeHeadlessLauncherTest {

    @TempDir
    Path temp;

    private SubscriberProperties properties(SubscriberProperties.Copilot permissions) {
        return new SubscriberProperties(temp.resolve("data").toString().replace('\\', '/'), List.of(temp.toString()), List.of(),
                SubscriberProperties.Runner.HEADLESS, 1, permissions,
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.NONE, AgentTool.CLAUDE, new SubscriberProperties.Claude(StandInAgent.COMMAND));
    }

    private List<String> run(SubscriberProperties props, StandInAgent agent, String id, Path repo, int exitCode) throws Exception {
        Path folder = new SessionFolders(props).create(id);
        Files.writeString(folder.resolve("prompt.md"), "the task");
        RunningSession session = new HeadlessLauncher(props, new SessionFolders(props), new LauncherScripts(), agent.environment("succeeded", exitCode))
                .launch(id, repo, "security-fixer");
        session.ended().get(90, TimeUnit.SECONDS);
        return agent.recordedLines();
    }

    @Test
    void aHeadlessClaudeCodeSessionIsStartedWithClaudeCodesArgumentsAndRecordsItsExit() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop");
        String head = StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();

        List<String> recorded = run(properties(new SubscriberProperties.Copilot("copilot", PermissionMode.ALLOW_ALL_TOOLS)), agent, id, repo, 0);

        assertThat(Path.of(recorded.get(0).substring("cwd=".length())).toRealPath()).isEqualTo(repo.toRealPath());
        assertThat(recorded).contains("arg=-p", "arg=--agent", "arg=security-fixer", "arg=--session-id", "arg=" + id, "arg=--permission-mode",
                "arg=bypassPermissions", "arg=--output-format", "arg=stream-json", "arg=--verbose", "arg=--add-dir");
        assertThat(recorded).as("Copilot-only arguments are not passed").doesNotContain("arg=--no-ask-user", "arg=--share", "arg=--log-dir",
                "arg=--allow-all-tools");
        assertThat(recorded).anyMatch(l -> l.startsWith("arg=Read and follow ") && l.endsWith("prompt.md"));
        assertThat(agent.promptCopy()).as("the prompt the first argument points to is readable").hasContent("the task");
        ExitRecord exit = ExitRecord.read(new SessionFolders(properties(new SubscriberProperties.Copilot("copilot", PermissionMode.ASK))).folder(id))
                .orElseThrow();
        assertThat(exit.exitCode()).isZero();
        assertThat(exit.headEnd()).isEqualTo(head);
        assertThat(repo.resolve(".appmod/result.json")).content().contains("succeeded");
    }

    @Test
    void theAllowListReachesClaudeCodeAsTwoSingleArgumentsAndNoBlanketPermission() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop2");
        StandInAgent.initRepository(repo);
        SubscriberProperties props = properties(new SubscriberProperties.Copilot("copilot", PermissionMode.ALLOW_LIST,
                List.of("Read", "Edit", "Bash(git *)"), List.of("Bash(curl *)", "Bash(git push *)")));

        List<String> recorded = run(props, agent, UUID.randomUUID().toString(), repo, 0);

        int allowed = recorded.indexOf("arg=--allowedTools");
        assertThat(allowed).isPositive();
        assertThat(recorded.get(allowed + 1)).as("one argument, with the rules joined").isEqualTo("arg=Read,Edit,Bash(git *)");
        int denied = recorded.indexOf("arg=--disallowedTools");
        assertThat(recorded.get(denied + 1)).isEqualTo("arg=Bash(curl *),Bash(git push *)");
        assertThat(recorded).doesNotContain("arg=--permission-mode", "arg=bypassPermissions");
    }

    @Test
    void aFailingClaudeCodeEndsWithItsExitCodeInTheRecord() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop3");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        SubscriberProperties props = properties(new SubscriberProperties.Copilot("copilot", PermissionMode.ALLOW_ALL_TOOLS));

        run(props, agent, id, repo, 3);

        assertThat(ExitRecord.read(new SessionFolders(props).folder(id)).orElseThrow().exitCode()).isEqualTo(3);
    }

    @Test
    void aClaudeCodeStartedFromInsideAnotherClaudeCodeSessionDoesNotSeeThatSessionButKeepsItsCredentials() throws Exception {
        StandInAgent agent = new StandInAgent(temp);
        Path repo = temp.resolve("shop4");
        StandInAgent.initRepository(repo);
        String id = UUID.randomUUID().toString();
        SubscriberProperties props = properties(new SubscriberProperties.Copilot("copilot", PermissionMode.ALLOW_ALL_TOOLS));
        Path folder = new SessionFolders(props).create(id);
        Files.writeString(folder.resolve("prompt.md"), "the task");
        java.util.Map<String, String> environment = new java.util.LinkedHashMap<>(agent.environment("succeeded", 0));
        environment.put("CLAUDECODE", "1");
        environment.put("CLAUDE_CODE_SESSION_ID", "the-parents-session");
        environment.put("ANTHROPIC_API_KEY", "a-credential");

        new HeadlessLauncher(props, new SessionFolders(props), new LauncherScripts(), environment).launch(id, repo, "security-fixer")
                .ended().get(90, TimeUnit.SECONDS);

        assertThat(agent.recordedLines()).as("the parent's session variables are gone and the credential is not").contains("claudecode=||a-credential");
    }
}
