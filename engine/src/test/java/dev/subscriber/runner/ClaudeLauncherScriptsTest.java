package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.config.SubscriberProperties.AgentTool;
import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.runner.LaunchPlan.Flavor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The scripts that start Claude Code: the same session, exit record and quoting as for Copilot, with Claude Code's own arguments. */
class ClaudeLauncherScriptsTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";
    private static final String HOSTILE = "C:\\work\\r&d 100% ^'x (1) é\\shop";

    private final LauncherScripts scripts = new LauncherScripts();

    @TempDir
    Path temp;

    private static LaunchPlan plan(Flavor flavor, String repository, PermissionMode mode) {
        return new LaunchPlan(ID, Path.of("C:\\appmod data\\sessions\\" + ID), Path.of(repository), "security-fixer", "claude", mode,
                List.of(), List.of(), flavor, AgentTool.CLAUDE);
    }

    private static LaunchPlan allowList(Flavor flavor, List<String> allowed, List<String> denied) {
        return new LaunchPlan(ID, Path.of("C:\\appmod data\\sessions\\" + ID), Path.of("C:\\work\\shop"), "security-fixer", "claude",
                PermissionMode.ALLOW_LIST, allowed, denied, flavor, AgentTool.CLAUDE);
    }

    @Test
    void theWindowScriptRunsClaudeCodeInteractivelyWithThePromptAsItsFirstMessage() {
        String s = scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ALLOW_ALL_TOOLS));

        assertThat(s).contains("$session = 'C:\\appmod data\\sessions\\" + ID + "'")
                .contains("Set-Location -LiteralPath 'C:\\work\\shop'")
                .contains("& claude \"Read and follow $session\\prompt.md\" --agent security-fixer --session-id " + ID
                        + " --name 'appmod fix 11111111' --permission-mode bypassPermissions --add-dir $session")
                .contains("$Host.UI.RawUI.WindowTitle = 'appmod fix 11111111'")
                .contains("} finally {").contains("Move-Item -Force -LiteralPath $temp -Destination (Join-Path $session 'exit.json')")
                .endsWith("exit $code\n");
        assertThat(s).as("interactive: no print mode and no copilot-only arguments").doesNotContain(" -p ").doesNotContain("--log-dir")
                .doesNotContain("--share").doesNotContain("--no-ask-user").doesNotContain("--allow-all-tools").doesNotContain("copilot");
        assertThat(s).doesNotContain("{{");
    }

    @Test
    void theHeadlessScriptsRunPrintModeAndKeepTheRunAsStreamJson() {
        String sh = scripts.render(new LaunchPlan(ID, Path.of("/data/sessions/" + ID), Path.of("/work/shop"), "security-fixer", "claude",
                PermissionMode.ALLOW_LIST, List.of("Read"), List.of(), Flavor.HEADLESS_SHELL, AgentTool.CLAUDE));
        String ps = scripts.render(allowList(Flavor.HEADLESS_POWERSHELL, List.of("Read"), List.of()));

        assertThat(sh).contains("claude -p \"Read and follow $session/prompt.md\" --agent security-fixer --session-id " + ID
                + " --name 'appmod fix 11111111' --allowedTools 'Read' --add-dir \"$session\" --output-format stream-json --verbose")
                .contains("trap write_exit EXIT").contains("exit.json").startsWith("#!/bin/sh");
        assertThat(ps).contains("& claude -p \"Read and follow $session\\prompt.md\" --agent security-fixer --session-id " + ID
                + " --name 'appmod fix 11111111' --allowedTools 'Read' --add-dir $session --output-format stream-json --verbose");
        assertThat(sh + ps).doesNotContain("--no-ask-user").doesNotContain("--log-dir").doesNotContain("--share").doesNotContain("{{");
    }

    @Test
    void theReopenScriptResumesTheSessionAndRecordsNothing() {
        String s = scripts.render(plan(Flavor.REOPEN_POWERSHELL, HOSTILE, PermissionMode.ASK));

        assertThat(s).contains("& claude --resume " + ID + " --add-dir $session")
                .contains("Set-Location -LiteralPath 'C:\\work\\r&d 100% ^''x (1) é\\shop'");
        assertThat(s).doesNotContain("exit.json").doesNotContain("--session-id").doesNotContain("prompt.md").doesNotContain("--permission-mode");
    }

    @Test
    void permissionModesBecomeClaudeCodesOwnArguments() {
        assertThat(scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ALLOW_ALL_TOOLS)))
                .contains(" --permission-mode bypassPermissions");
        assertThat(scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ASK)))
                .as("the person approves in the window").doesNotContain("--permission-mode").doesNotContain("--allowedTools");
        assertThat(scripts.render(plan(Flavor.HEADLESS_SHELL, "/work/shop", PermissionMode.ASK)))
                .as("nobody can answer headless, as for Copilot").contains(" --permission-mode bypassPermissions");
    }

    @Test
    void theAllowListIsOneCommaJoinedQuotedArgumentForEachOfTheTwoLists() {
        List<String> allowed = List.of("Read", "Edit", "Write", "Bash(git *)", "Bash(node *)");
        List<String> denied = List.of("Bash(curl *)", "Bash(git push *)");

        for (Flavor flavor : List.of(Flavor.WINDOW_POWERSHELL, Flavor.HEADLESS_POWERSHELL, Flavor.HEADLESS_SHELL)) {
            String s = scripts.render(allowList(flavor, allowed, denied));
            assertThat(s).as(flavor.name()).contains(" --allowedTools 'Read,Edit,Write,Bash(git *),Bash(node *)'")
                    .contains(" --disallowedTools 'Bash(curl *),Bash(git push *)'")
                    .doesNotContain("--permission-mode").doesNotContain("bypassPermissions").doesNotContain("--allow-tool ");
        }
        assertThat(scripts.render(allowList(Flavor.WINDOW_POWERSHELL, allowed, List.of()))).doesNotContain("--disallowedTools");
    }

    @Test
    void aRuleThatCouldBecomeACommandIsRefusedForClaudeCodeToo() {
        for (String hostile : List.of("Bash(git *)'; calc; '", "Bash(git *) $(id)", "Read,Write", "Bash(git `id`)", "Bash(git *)\"")) {
            assertThatThrownBy(() -> allowList(Flavor.HEADLESS_SHELL, List.of(hostile), List.of()))
                    .as(hostile).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void thePowerShellParserAcceptsEveryClaudeCodeScriptEvenWithAHostilePath() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "needs Windows PowerShell");
        Path folder = Files.createDirectories(temp.resolve("parse"));
        Path checker = folder.resolve("check-parse.ps1");
        Files.writeString(checker, """
                param([string]$Path)
                $tokens = $null; $errors = $null
                [void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
                if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Output $_.Message }; exit 1 }
                Write-Output 'OK'
                """);
        for (Flavor flavor : List.of(Flavor.WINDOW_POWERSHELL, Flavor.HEADLESS_POWERSHELL, Flavor.REOPEN_POWERSHELL)) {
            for (PermissionMode mode : List.of(PermissionMode.ALLOW_ALL_TOOLS, PermissionMode.ASK, PermissionMode.ALLOW_LIST)) {
                LaunchPlan p = new LaunchPlan(ID, folder, Path.of(HOSTILE), "security-fixer", "claude", mode,
                        mode == PermissionMode.ALLOW_LIST ? List.of("Read", "Bash(git *)") : List.of(),
                        mode == PermissionMode.ALLOW_LIST ? List.of("Bash(curl *)") : List.of(), flavor, AgentTool.CLAUDE);
                Path script = scripts.write(p);
                Process proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", checker.toString(), script.toString()).redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
                assertThat(proc.exitValue()).as("%s %s: %s", flavor, mode, output).isZero();
            }
        }
    }

    @Test
    void theShellParserAcceptsTheClaudeCodeShellScriptEvenWithAHostilePath() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("sh"));
        LaunchPlan p = new LaunchPlan(ID, folder, Path.of("/home/o'brien/r&d 100% ^x $(rm -rf) `id`"), "security-fixer", "claude",
                PermissionMode.ALLOW_LIST, List.of("Read", "Bash(git *)"), List.of("Bash(curl *)"), Flavor.HEADLESS_SHELL, AgentTool.CLAUDE);
        Path script = scripts.write(p);
        Process proc;
        try {
            proc = new ProcessBuilder("sh", "-n", script.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort("no sh on this machine");
            return;
        }
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(proc.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(proc.exitValue()).as(output).isZero();
    }

    @Test
    void everyClaudeCodeScriptForgetsTheVariablesOfAParentClaudeCodeSessionButNotTheCredentials() {
        for (Flavor flavor : List.of(Flavor.WINDOW_POWERSHELL, Flavor.HEADLESS_POWERSHELL, Flavor.REOPEN_POWERSHELL, Flavor.HEADLESS_SHELL)) {
            String s = scripts.render(plan(flavor, "C:\\work\\shop", PermissionMode.ASK));
            assertThat(s).as(flavor.name()).contains("CLAUDECODE").contains("CLAUDE_CODE_SESSION_ID").contains("CLAUDE_CODE_CHILD_SESSION")
                    .doesNotContain("ANTHROPIC").doesNotContain("OAUTH");
        }
        assertThat(scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ASK)))
                .contains("Remove-Item -LiteralPath ('Env:' + $name) -ErrorAction SilentlyContinue");
        assertThat(scripts.render(plan(Flavor.HEADLESS_SHELL, "/work/shop", PermissionMode.ASK))).contains("\nunset CLAUDECODE ");
        assertThat(scripts.render(new LaunchPlan(ID, Path.of("C:\\appmod data\\sessions\\" + ID), Path.of("C:\\work\\shop"), "security-fixer", "copilot",
                PermissionMode.ASK, Flavor.WINDOW_POWERSHELL))).as("the Copilot scripts are untouched").doesNotContain("CLAUDECODE");
    }
}
