package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class LauncherScriptsTest {

    static final String ID = "11111111-2222-3333-4444-555555555555";
    static final String HOSTILE = "C:\\work\\r&d 100% ^'x (1) é\\shop";

    private final LauncherScripts scripts = new LauncherScripts();

    @TempDir
    Path temp;

    static LaunchPlan plan(Flavor flavor, String repository, PermissionMode mode) {
        return new LaunchPlan(ID, Path.of("C:\\appmod data\\sessions\\" + ID), Path.of(repository), "security-fixer", "copilot",
                mode, flavor);
    }

    @Test
    void theWindowScriptRunsTheAgentInteractivelyAndRecordsHowItEnded() {
        String s = scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ALLOW_ALL_TOOLS));
        assertThat(s).contains("$session = 'C:\\appmod data\\sessions\\" + ID + "'")
                .contains("Set-Location -LiteralPath 'C:\\work\\shop'")
                .contains("& copilot --agent security-fixer --session-id " + ID + " --name 'appmod fix 11111111' --allow-all-tools")
                .contains("--add-dir $session --log-dir (Join-Path $session 'logs') -i \"Read and follow $session\\prompt.md\"")
                .contains("$Host.UI.RawUI.WindowTitle = 'appmod fix 11111111'")
                .contains("try {").contains("} finally {")
                .contains("git -C 'C:\\work\\shop' rev-parse HEAD")
                .contains("Move-Item -Force -LiteralPath $temp -Destination (Join-Path $session 'exit.json')")
                .contains("UTF8Encoding($false)")
                .endsWith("exit $code\n");
        assertThat(s).as("the window closes with the script").doesNotContain("-NoExit")
                .as("not allowed together with --session-id").doesNotContain("--trust-folder")
                .as("a person can answer questions").doesNotContain("--no-ask-user")
                .as("interactive").doesNotContain(" -p ").doesNotContain("--share");
        assertThat(s).doesNotContain("{{");
    }

    @Test
    void askModeAddsNoPermissionFlag() {
        String s = scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ASK));
        assertThat(s).doesNotContain("--allow-all-tools").contains("--name 'appmod fix 11111111' --add-dir");
    }

    @Test
    void theHeadlessScriptsAreNonInteractiveAndKeepATranscript() {
        for (Flavor flavor : List.of(Flavor.HEADLESS_POWERSHELL, Flavor.HEADLESS_SHELL)) {
            String s = scripts.render(plan(flavor, flavor == Flavor.HEADLESS_SHELL ? "/work/shop" : "C:\\work\\shop", PermissionMode.ASK));
            assertThat(s).as(flavor.name()).contains("--allow-all-tools --no-ask-user")
                    .contains("--session-id " + ID).contains("-p \"Read and follow $session")
                    .contains("--share").contains("transcript.md")
                    .doesNotContain("--trust-folder").doesNotContain(" -i ").doesNotContain("{{");
        }
    }

    @Test
    void aHostileRepositoryPathAppearsOnlyInsideQuotedLiterals() {
        String s = scripts.render(plan(Flavor.WINDOW_POWERSHELL, HOSTILE, PermissionMode.ALLOW_ALL_TOOLS));
        String quoted = "'C:\\work\\r&d 100% ^''x (1) é\\shop'";
        assertThat(s).contains("Set-Location -LiteralPath " + quoted).contains("git -C " + quoted + " rev-parse HEAD");
        assertThat(s.split("r&d", -1).length - 1).as("only the two quoted literals mention the folder").isEqualTo(2);
        String agentLine = s.lines().filter(l -> l.startsWith("    & copilot")).findFirst().orElseThrow();
        assertThat(agentLine).doesNotContain("r&d").doesNotContain("%").doesNotContain("^");
    }

    @Test
    void textThatLooksLikeAPlaceholderInAPathIsNotExpanded() {
        String s = scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\{{command}}\\{{agent}}\\shop", PermissionMode.ALLOW_ALL_TOOLS));
        assertThat(s).contains("Set-Location -LiteralPath 'C:\\{{command}}\\{{agent}}\\shop'")
                .contains("--agent security-fixer").contains("& copilot --agent");
    }

    @Test
    void theShellQuotingClosesAndReopensTheStringAroundAQuote() {
        // Path.of uses the separators of the operating system, so the expectation is built from what the path prints as.
        LaunchPlan plan = plan(Flavor.HEADLESS_SHELL, "placeholder", PermissionMode.ALLOW_ALL_TOOLS);
        Path repository = Path.of("home", "o'brien", "r&d 100%");
        LaunchPlan hostile = new LaunchPlan(plan.sessionId(), plan.sessionFolder(), repository, plan.agent(), plan.command(),
                plan.permissionMode(), plan.flavor());
        String escaped = repository.toString().replace("'", "'\\''");
        String s = scripts.render(hostile);
        assertThat(s).contains("repository='" + escaped + "'").contains("cd \"$repository\"")
                .contains("trap write_exit EXIT").startsWith("#!/bin/sh");
        assertThat(escaped).contains("o'\\''brien");
        assertThat(LauncherScripts.shQuoted("a'b")).isEqualTo("a'\\''b");
        assertThat(LauncherScripts.psQuoted("a'b''c")).isEqualTo("a''b''''c");
    }

    @Test
    void aPlanWithAnUnsafeValueIsRefusedBeforeAnyScriptIsMade() {
        Path folder = Path.of("C:\\data\\sessions\\" + ID);
        Path repo = Path.of("C:\\work\\shop");
        assertThatThrownBy(() -> new LaunchPlan("not-a-uuid", folder, repo, "a", "copilot", PermissionMode.ASK, Flavor.WINDOW_POWERSHELL))
                .hasMessageContaining("session id");
        assertThatThrownBy(() -> new LaunchPlan(ID, folder, repo, "a;calc", "copilot", PermissionMode.ASK, Flavor.WINDOW_POWERSHELL))
                .hasMessageContaining("agent name");
        assertThatThrownBy(() -> new LaunchPlan(ID, folder, repo, "a", "co pilot", PermissionMode.ASK, Flavor.WINDOW_POWERSHELL))
                .hasMessageContaining("command name");
        assertThatThrownBy(() -> new LaunchPlan(ID, folder, repo, "a", "C:\\x\\copilot", PermissionMode.ASK, Flavor.WINDOW_POWERSHELL))
                .hasMessageContaining("command name");
        // Windows refuses a control character in a path before the plan sees it; other systems allow it, and the plan refuses it.
        assertThatThrownBy(() -> new LaunchPlan(ID, folder, Path.of("C:\\work\\a\u0007b"), "a", "copilot", PermissionMode.ASK,
                Flavor.WINDOW_POWERSHELL)).isInstanceOfAny(java.nio.file.InvalidPathException.class, IllegalArgumentException.class);
        assertThatThrownBy(() -> new LaunchPlan(ID, Path.of("C:\\da'ta\\" + ID), repo, "a", "copilot", PermissionMode.ASK,
                Flavor.WINDOW_POWERSHELL)).hasMessageContaining("quote");
    }

    @Test
    void powerShellScriptsStartWithAByteOrderMarkSoNonAsciiNamesSurviveAndShellScriptsDoNot() throws IOException {
        Path folder = Files.createDirectories(temp.resolve("session"));
        LaunchPlan ps = new LaunchPlan(ID, folder, Path.of("C:\\work\\é"), "a", "copilot", PermissionMode.ASK, Flavor.WINDOW_POWERSHELL);
        byte[] bytes = Files.readAllBytes(scripts.write(ps));
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        assertThat(bytes[1]).isEqualTo((byte) 0xBB);
        assertThat(bytes[2]).isEqualTo((byte) 0xBF);
        assertThat(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8)).contains("'C:\\work\\é'");
        assertThat(folder.resolve("run.ps1")).exists();

        LaunchPlan sh = new LaunchPlan(ID, folder, Path.of("/work/é"), "a", "copilot", PermissionMode.ASK, Flavor.HEADLESS_SHELL);
        byte[] shBytes = Files.readAllBytes(scripts.write(sh));
        assertThat(shBytes[0]).isEqualTo((byte) '#');
        assertThat(folder.resolve("run.sh")).exists();
    }

    @Test
    void thePowerShellParserAcceptsEveryPowerShellScriptEvenWithAHostilePath() throws Exception {
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
            for (String repo : List.of("C:\\work\\shop", HOSTILE, "C:\\{{command}}\\$USERNAME\\`n\\shop")) {
                LaunchPlan p = new LaunchPlan(ID, folder, Path.of(repo), "security-fixer", "copilot", PermissionMode.ALLOW_ALL_TOOLS, flavor);
                Path script = scripts.write(p);
                Process proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", checker.toString(), script.toString()).redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
                assertThat(proc.exitValue()).as("%s with %s: %s", flavor, repo, output).isZero();
                assertThat(output.trim()).isEqualTo("OK");
            }
        }
    }

    @Test
    void theShellParserAcceptsTheShellScriptEvenWithAHostilePath() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("sh"));
        LaunchPlan p = new LaunchPlan(ID, folder, Path.of("/home/o'brien/r&d 100% ^x $(rm -rf) `id`"), "security-fixer", "copilot",
                PermissionMode.ALLOW_ALL_TOOLS, Flavor.HEADLESS_SHELL);
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
    void theReopenScriptResumesTheSessionAndRecordsNothing() {
        String s = scripts.render(plan(Flavor.REOPEN_POWERSHELL, HOSTILE, PermissionMode.ALLOW_ALL_TOOLS));

        assertThat(s).contains("--resume " + ID).contains("--allow-all-tools");
        assertThat(s).doesNotContain("exit.json").doesNotContain("--session-id").doesNotContain(" -i ").doesNotContain("prompt.md");
        assertThat(s).contains("Set-Location -LiteralPath 'C:\\work\\r&d 100% ^''x (1) é\\shop'");
        assertThat(scripts.render(plan(Flavor.REOPEN_POWERSHELL, "C:\\work\\shop", PermissionMode.ASK))).doesNotContain("--allow-all-tools");
    }
    // ---- allow-list mode ----

    private static LaunchPlan allowList(Flavor flavor, String repository, List<String> allowed, List<String> denied) {
        return new LaunchPlan(ID, Path.of("C:\\appmod data\\sessions\\" + ID), Path.of(repository), "security-fixer", "copilot",
                PermissionMode.ALLOW_LIST, allowed, denied, flavor);
    }

    @Test
    void theAllowListBecomesQuotedAllowAndDenyArgumentsAndNoBlanketFlagInEveryFlavor() {
        List<String> allowed = List.of("shell(git:*)", "write(C:/work/**)", "shell(git push)");
        List<String> denied = List.of("shell(curl:*)");
        for (Flavor flavor : Flavor.values()) {
            String s = scripts.render(allowList(flavor, flavor == Flavor.HEADLESS_SHELL ? "/work/shop" : "C:\\work\\shop", allowed, denied));
            assertThat(s).as(flavor.name()).contains(" --allow-tool 'shell(git:*)' --allow-tool 'write(C:/work/**)' --allow-tool 'shell(git push)'"
                    + " --deny-tool 'shell(curl:*)'").doesNotContain("--allow-all-tools").doesNotContain("{{");
        }
    }

    @Test
    void theOtherModesKeepTheirFlagsAndHeadlessStillGetsEverythingWhenNobodyCanAnswer() {
        assertThat(scripts.render(plan(Flavor.HEADLESS_SHELL, "/work/shop", PermissionMode.ALLOW_ALL_TOOLS))).contains("--allow-all-tools --no-ask-user");
        assertThat(scripts.render(plan(Flavor.HEADLESS_SHELL, "/work/shop", PermissionMode.ASK))).contains("--allow-all-tools --no-ask-user");
        assertThat(scripts.render(plan(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", PermissionMode.ASK))).doesNotContain("--allow-all-tools")
                .doesNotContain("--allow-tool");
    }

    @Test
    void aRulePlacedInAPlanThatCouldRunAsACommandIsRefused() {
        for (String rule : List.of("shell(git:*); calc", "a'b", "a\"b", "a$(calc)", "a`b", "a&b", "a|b", "a%PATH%b", "a\nb", "x".repeat(201), "")) {
            assertThatThrownBy(() -> allowList(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", List.of("shell(git:*)", rule), List.of()))
                    .as(rule).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("allowed tool rule");
            assertThatThrownBy(() -> allowList(Flavor.HEADLESS_SHELL, "/work/shop", List.of("shell(git:*)"), List.of(rule)))
                    .as("deny " + rule).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("denied tool rule");
        }
        assertThatThrownBy(() -> allowList(Flavor.WINDOW_POWERSHELL, "C:\\work\\shop", List.of(), List.of("shell(curl:*)")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs an allow rule");
        assertThatThrownBy(() -> new LaunchPlan(ID, Path.of("C:\\data\\" + ID), Path.of("C:\\work"), "a", "copilot", PermissionMode.ASK,
                List.of("shell(git:*)"), List.of(), Flavor.WINDOW_POWERSHELL)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only used with the allow-list mode");
    }

    @Test
    void everyFlavorWithRulesPassesTheParsers() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("allow-parse"));
        List<String> allowed = List.of("shell(git:*)", "write(C:\\work\\**)", "shell(Test-Path)", "read(~/notes/todo.md)");
        List<String> denied = List.of("shell(git push:*)");
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            Path checker = folder.resolve("check-parse.ps1");
            Files.writeString(checker, """
                    param([string]$Path)
                    $tokens = $null; $errors = $null
                    [void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$tokens, [ref]$errors)
                    if ($errors.Count -gt 0) { $errors | ForEach-Object { Write-Output $_.Message }; exit 1 }
                    Write-Output 'OK'
                    """);
            for (Flavor flavor : List.of(Flavor.WINDOW_POWERSHELL, Flavor.HEADLESS_POWERSHELL, Flavor.REOPEN_POWERSHELL)) {
                Path script = scripts.write(new LaunchPlan(ID, folder, Path.of(HOSTILE), "security-fixer", "copilot", PermissionMode.ALLOW_LIST,
                        allowed, denied, flavor));
                Process proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", checker.toString(), script.toString()).redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
                assertThat(output.trim()).as(flavor + ": " + output).isEqualTo("OK");
            }
        }
        Path script = scripts.write(new LaunchPlan(ID, folder, Path.of("/home/o'brien/shop"), "security-fixer", "copilot",
                PermissionMode.ALLOW_LIST, allowed, denied, Flavor.HEADLESS_SHELL));
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
}
