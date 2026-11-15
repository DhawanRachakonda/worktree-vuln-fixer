import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Validates a security-audit skill run directory with the skill's own validators.
 *
 * <pre>java ValidateRun.java &lt;run-dir&gt; [--skill-dir DIR]</pre>
 *
 * <p>Checks {@code <run-dir>/findings.json} with {@code ValidateFindings.java} and {@code <run-dir>/coverage-ledger.json} with
 * {@code ValidateCoverageLedger.java}, both of the skill folder, each run as its own process. The validators read a file without following
 * links on every operating system, so there is no reduced mode. A failure of any kind (a validation error, a missing file, bad JSON) is a failure.
 *
 * <p>Last stdout line: one JSON object. Exit codes: 0 both valid, 1 invalid, 2 usage. Needs JDK 25.
 */
public final class ValidateRun {

    private static final int TIMEOUT_SECONDS = 120;

    private record Result(String label, boolean pass, String detail) {
    }

    private ValidateRun() {
    }

    private static int usage(String message) {
        System.err.println("validate-run: " + message);
        System.err.println("usage: java ValidateRun.java <run-dir> [--skill-dir DIR]");
        return 2;
    }

    /** The folder of this source file ({@code java <folder>/ValidateRun.java}), from the launcher's command line. */
    private static Path scriptFolder() {
        String command = System.getProperty("sun.java.command", "");
        int launcher = command.indexOf("SourceLauncher ");
        if (launcher < 0) {
            return null;
        }
        String rest = command.substring(launcher + "SourceLauncher ".length());
        int end = rest.indexOf(".java");
        if (end < 0) {
            return null;
        }
        return Path.of(rest.substring(0, end + ".java".length())).toAbsolutePath().getParent();
    }

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() < 25) {
            System.err.println("validate-run: needs JDK 25 or later, and this is JDK " + Runtime.version().feature());
            System.exit(2);
        }
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        String runDirArgument = null;
        Path skillDir = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--skill-dir")) {
                if (i + 1 >= args.length) {
                    return usage("--skill-dir needs a value");
                }
                skillDir = Path.of(args[++i]).toAbsolutePath().normalize();
            } else if (args[i].startsWith("--")) {
                return usage("unknown option " + args[i]);
            } else if (runDirArgument == null) {
                runDirArgument = args[i];
            } else {
                return usage("only one run directory may be given");
            }
        }
        if (runDirArgument == null) {
            return usage("the skill run directory is required");
        }
        Path runDir = Path.of(runDirArgument).toAbsolutePath().normalize();
        if (skillDir == null) {
            skillDir = findSkillDir();
        }
        if (!Files.isDirectory(runDir)) {
            return usage("run directory not found: " + runDir);
        }

        List<Result> results = new ArrayList<>();
        results.add(check("findings", runDir.resolve("findings.json"), skillDir.resolve("ValidateFindings.java")));
        results.add(check("coverage-ledger", runDir.resolve("coverage-ledger.json"), skillDir.resolve("ValidateCoverageLedger.java")));

        for (Result r : results) {
            String line = "[%s] %s: %s".formatted(r.label, r.pass ? "PASS" : "FAIL", r.detail);
            (r.pass ? System.out : System.err).println(line);
        }
        boolean ok = results.stream().allMatch(Result::pass);
        StringBuilder checks = new StringBuilder();
        for (Result r : results) {
            checks.append(checks.isEmpty() ? "" : ",").append("\"%s\":{\"status\":\"%s\"}".formatted(r.label, r.pass ? "pass" : "fail"));
        }
        System.out.println("{\"valid\":%s,\"checks\":{%s}}".formatted(ok, checks));
        return ok ? 0 : 1;
    }

    /** The kit puts the skill beside the tools (.github/skills) for Copilot, and in .claude/skills when it was installed for Claude Code only. */
    private static Path findSkillDir() {
        Path folder = scriptFolder();
        Path repoRoot = folder == null ? Path.of("").toAbsolutePath() : folder.getParent().getParent().getParent();
        Path github = repoRoot.resolve(".github/skills/security-audit");
        Path claude = repoRoot.resolve(".claude/skills/security-audit");
        return Files.exists(github.resolve("ValidateFindings.java")) ? github
                : Files.exists(claude.resolve("ValidateFindings.java")) ? claude : github;
    }

    private static Result check(String label, Path file, Path validator) {
        if (!Files.exists(file)) {
            return new Result(label, false, file.getFileName() + " not found");
        }
        if (!Files.exists(validator)) {
            return new Result(label, false, "the validator " + validator.getFileName() + " was not found in " + validator.getParent());
        }
        Path stdout = null;
        Path stderr = null;
        try {
            stdout = Files.createTempFile("validate-run-out", ".txt");
            stderr = Files.createTempFile("validate-run-err", ".txt");
            Path java = Path.of(System.getProperty("java.home"), "bin", "java");
            // Output goes to files, so a full pipe can never stop the validator.
            Process process = new ProcessBuilder(java.toString(), validator.toString(), file.toString())
                    .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(label, false, "the validator did not finish in " + TIMEOUT_SECONDS + " seconds");
            }
            String out = Files.readString(stdout, StandardCharsets.UTF_8).trim();
            String err = Files.readString(stderr, StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 ? new Result(label, true, out) : new Result(label, false, err);
        } catch (IOException e) {
            return new Result(label, false, "could not run the validator: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(label, false, "interrupted");
        } finally {
            for (Path temporary : new Path[] {stdout, stderr}) {
                try {
                    if (temporary != null) {
                        Files.deleteIfExists(temporary);
                    }
                } catch (IOException ignored) {
                    // a temporary file the system cleans up later
                }
            }
        }
    }
}
