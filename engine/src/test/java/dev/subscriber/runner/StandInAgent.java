package dev.subscriber.runner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the Copilot CLI and for Claude Code (the prompt is found wherever it stands among the arguments) named {@code standin-copilot}, placed in a folder that goes first on the PATH of the launched
 * process. It records its working directory and arguments, copies the prompt file it is told to read, writes
 * {@code .appmod/result.json} in the repository with a chosen status, and exits with a chosen code.
 */
final class StandInAgent {

    static final String COMMAND = "standin-copilot";

    private final Path binDir;
    private final Path record;

    StandInAgent(Path folder) throws IOException {
        this.binDir = Files.createDirectories(folder.resolve("bin"));
        this.record = folder.resolve("standin-record.txt");
        Files.writeString(binDir.resolve(COMMAND + ".ps1"), POWERSHELL);
        Path sh = binDir.resolve(COMMAND);
        Files.writeString(sh, SHELL.replace("\r\n", "\n"));
        sh.toFile().setExecutable(true);
    }

    /** The environment for a launched process: PATH finds the stand-in, and the variables choose its behaviour. */
    Map<String, String> environment(String status, int exitCode) {
        Map<String, String> env = new LinkedHashMap<>();
        String pathKey = System.getenv().keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        env.put(pathKey, binDir + File.pathSeparator + System.getenv(pathKey));
        env.put("STANDIN_RECORD", record.toString());
        env.put("STANDIN_STATUS", status == null ? "" : status);
        env.put("STANDIN_EXIT", Integer.toString(exitCode));
        return env;
    }

    Path record() {
        return record;
    }

    Path promptCopy() {
        return Path.of(record + ".prompt");
    }

    /** The text of the record file as lines. */
    java.util.List<String> recordedLines() throws IOException {
        return Files.readAllLines(record);
    }

    /** Runs git in a folder and returns its trimmed output. */
    static String git(Path folder, String... args) throws IOException, InterruptedException {
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("git", "-C", folder.toString()));
        command.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out.trim();
    }

    /** A git repository with one commit; returns its HEAD. */
    static String initRepository(Path folder) throws IOException, InterruptedException {
        Files.createDirectories(folder);
        git(folder, "init", "-q");
        git(folder, "config", "user.email", "test@example.invalid");
        git(folder, "config", "user.name", "test");
        Files.writeString(folder.resolve("README.md"), "x");
        git(folder, "add", "-A");
        git(folder, "commit", "-q", "-m", "first");
        return git(folder, "rev-parse", "HEAD");
    }

    private static final String POWERSHELL = """
            $record = $env:STANDIN_RECORD
            $lines = @(('cwd=' + (Get-Location).Path), ('inputRedirected=' + [Console]::IsInputRedirected), ('outputRedirected=' + [Console]::IsOutputRedirected), ('windowTitle=' + $Host.UI.RawUI.WindowTitle), ('claudecode=' + $env:CLAUDECODE + '|' + $env:CLAUDE_CODE_SESSION_ID + '|' + $env:ANTHROPIC_API_KEY)) + @($args | ForEach-Object { 'arg=' + $_ })
            [System.IO.File]::WriteAllLines($record, [string[]]$lines)
            $last = [string]($args | Where-Object { ([string]$_) -like 'Read and follow *' } | Select-Object -First 1)
            $promptPath = $last -replace '^Read and follow ', ''
            if (Test-Path -LiteralPath $promptPath) { Copy-Item -LiteralPath $promptPath -Destination ($record + '.prompt') }
            if ($env:STANDIN_STATUS) {
                New-Item -ItemType Directory -Force -Path '.appmod' | Out-Null
                [System.IO.File]::WriteAllText((Join-Path (Get-Location).Path '.appmod\\result.json'), '{"status":"' + $env:STANDIN_STATUS + '"}')
            }
            exit ([int]$env:STANDIN_EXIT)
            """;

    private static final String SHELL = """
            #!/bin/sh
            { echo "cwd=$(pwd)"; echo "claudecode=$CLAUDECODE|$CLAUDE_CODE_SESSION_ID|$ANTHROPIC_API_KEY"; for a in "$@"; do echo "arg=$a"; done; } > "$STANDIN_RECORD"
            for a in "$@"; do case "$a" in "Read and follow "*) last=$a;; esac; done
            prompt=${last#Read and follow }
            [ -f "$prompt" ] && cp "$prompt" "$STANDIN_RECORD.prompt"
            if [ -n "$STANDIN_STATUS" ]; then
                mkdir -p .appmod
                printf '{"status":"%s"}' "$STANDIN_STATUS" > .appmod/result.json
            fi
            exit ${STANDIN_EXIT:-0}
            """;
}
