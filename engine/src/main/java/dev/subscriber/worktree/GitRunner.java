package dev.subscriber.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs git without a shell: an argument list, the repository as the working directory, no prompt for credentials, a time limit and
 * a cap on the output that is kept. The hooks of the repository are switched off for every command (git is pointed at an empty
 * folder), so a hook cannot run because the engine touched the repository.
 */
class GitRunner {

    /** What git answered. */
    record Result(int exitCode, String output) {

        boolean ok() {
            return exitCode == 0;
        }

        /** git's own error line when there is one; a warning or a hint that comes first (line-ending notes, advice) is not the reason. */
        String reason() {
            List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            return lines.stream().filter(l -> l.startsWith("fatal:") || l.startsWith("error:")).findFirst()
                    .orElse(lines.stream().filter(l -> !l.startsWith("warning:") && !l.startsWith("hint:")).findFirst().orElse(firstLine()));
        }

        String firstLine() {
            String text = output.strip();
            int end = text.indexOf('\n');
            return (end < 0 ? text : text.substring(0, end)).strip();
        }
    }

    private static final int OUTPUT_LIMIT = 64 * 1024;
    private static final String TIMED_OUT = "git did not finish in %d seconds";

    private final Path noHooks;
    private final long timeoutSeconds;

    GitRunner(Path noHooks, long timeoutSeconds) {
        this.noHooks = noHooks;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Ends git and everything it started (a remote helper that hangs would otherwise outlive git and keep files open). */
    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    Result run(Path workingDirectory, String... args) throws IOException {
        return run(workingDirectory, Map.of(), args);
    }

    /** As above with extra environment variables (for example GIT_INDEX_FILE); GIT_DIR and GIT_WORK_TREE are still removed first. */
    Result run(Path workingDirectory, Map<String, String> environment, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + noHooks, "-c", "core.askPass="));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
        // Nothing from the engine's own environment may point git at another repository, and git must never wait for a prompt.
        builder.environment().keySet().removeIf(key -> key.equalsIgnoreCase("GIT_DIR") || key.equalsIgnoreCase("GIT_WORK_TREE")
                || key.equalsIgnoreCase("GIT_INDEX_FILE") || key.equalsIgnoreCase("GIT_COMMON_DIR")
                // a helper that asks a person (a dialog from an editor, a terminal prompt) must never be started by an unattended engine
                || key.equalsIgnoreCase("GIT_ASKPASS") || key.equalsIgnoreCase("SSH_ASKPASS") || key.equalsIgnoreCase("GCM_INTERACTIVE"));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().putAll(environment);
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readNBytes(OUTPUT_LIMIT), StandardCharsets.UTF_8);
            } catch (IOException _) {
                return "";
            }
        });
        try {
            String text = output.get(timeoutSeconds, TimeUnit.SECONDS);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                killTree(process);
                throw new IOException(TIMED_OUT.formatted(timeoutSeconds));
            }
            return new Result(process.exitValue(), text);
        } catch (TimeoutException _) {
            killTree(process);
            throw new IOException(TIMED_OUT.formatted(timeoutSeconds));
        } catch (InterruptedException _) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for git");
        } catch (ExecutionException _) {
            killTree(process);
            throw new IOException("git output could not be read");
        }
    }
}
