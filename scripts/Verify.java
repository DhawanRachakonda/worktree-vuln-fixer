import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks every change must pass before it is committed or pushed, by a person or by an agent: {@code java scripts/Verify.java}.
 *
 * <p>No arguments runs everything. {@code --quick} runs only the fast checks (the installer tests and the OpenSpec validation, about a minute),
 * which is what the pre-commit hook uses; the pre-push hook and the CI workflow run all of them. {@code --live} adds the engine's
 * opt-in tests that start a real NATS server in a Docker container (or the program named by {@code NATS_SERVER_EXE}, when that is set). Exit code 0 means every check passed or was skipped
 * with a reason; anything else means something failed and the change is not ready.
 */
public class Verify {

    record Check(String name, Path directory, List<String> command, boolean slow) {}

    record Outcome(String name, String result, Duration took) {}

    /** True when a Docker daemon answers, which is all the live tests need to start their NATS server. */
    static boolean dockerAnswers() {
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (java.io.IOException | InterruptedException _) {
            return false;
        }
    }

    public static void main(String[] args) throws Exception {
        boolean quick = List.of(args).contains("--quick");
        boolean live = List.of(args).contains("--live");
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.isDirectory(root.resolve("kit")) || !Files.isDirectory(root.resolve("engine"))) {
            System.err.println("Run this from the repository root: java scripts/Verify.java");
            System.exit(2);
        }
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        String wrapperName = windows ? "gradlew.bat" : "gradlew";
        String gradlew = root.resolve("engine").resolve(wrapperName).toString();
        List<String> engineBuild = new ArrayList<>(List.of(gradlew, "build", "--console=plain"));
        if (live) {
            if (System.getenv("NATS_SERVER_EXE") == null && !dockerAnswers()) {
                System.err.println("--live needs a running Docker daemon (the live tests start a NATS server in a container) or NATS_SERVER_EXE pointing at a nats-server program.");
                System.exit(2);
            }
            engineBuild.add("-Pnats-tests");
        }

        List<Check> checks = List.of(
                new Check("kit: installer tests", root.resolve("kit"), List.of("java", "tests/installer/InstallerTests.java"), false),
                new Check("kit: git step of appmod init tests", root.resolve("kit"), List.of("java", "tests/installer/GitStepTests.java"), false),
                new Check("kit: appmod setup and start tests", root.resolve("kit"), List.of("java", "tests/setup/SetupStartTests.java"), true),
                new Check("kit: security-audit tool tests", root.resolve("kit"), List.of("java", "tests/security-audit/ToolTests.java"), true),
                new Check("kit: security-audit validator tests", root.resolve("kit"), List.of("java", "tests/security-audit/ValidatorTests.java"), true),
                new Check("kit: security-fixer tool tests", root.resolve("kit"), List.of("java", "tests/security-fixer/ToolTests.java"), true),
                new Check("engine: build and tests", root.resolve("engine"), engineBuild, true),
                new Check("intel-service: build and tests", root.resolve("intel-service"), List.of(root.resolve("intel-service").resolve(wrapperName).toString(), "build", "--console=plain"), true));

        List<Outcome> outcomes = new ArrayList<>();
        for (Check check : checks) {
            if (quick && check.slow()) {
                outcomes.add(new Outcome(check.name(), "SKIPPED (--quick)", Duration.ZERO));
                continue;
            }
            outcomes.add(run(check));
        }
        outcomes.add(openspec(root));

        System.out.println();
        System.out.println("Verification summary");
        for (Outcome outcome : outcomes) {
            System.out.printf("  %-40s %-28s %ds%n", outcome.name(), outcome.result(), outcome.took().toSeconds());
        }
        boolean failed = outcomes.stream().anyMatch(o -> o.result().startsWith("FAILED"));
        System.out.println(failed ? "NOT READY: fix the failures above before you commit or push." : "READY: every check passed or was skipped for a stated reason.");
        System.exit(failed ? 1 : 0);
    }

    private static Outcome run(Check check) {
        System.out.println("== " + check.name());
        long start = System.nanoTime();
        try {
            int exit = exec(check.name(), check.command(), check.directory());
            return new Outcome(check.name(), exit == 0 ? "PASSED" : "FAILED (exit " + exit + ")", since(start));
        } catch (IOException e) {
            return new Outcome(check.name(), "FAILED (" + e.getMessage() + ")", since(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(check.name(), "FAILED (interrupted)", since(start));
        }
    }

    /** The specs are validated when the OpenSpec CLI is installed. Skipping says so; CI installs it, so it is never skipped there. */
    private static Outcome openspec(Path root) {
        String name = "openspec: validate specs --strict";
        System.out.println("== " + name);
        long start = System.nanoTime();
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        try {
            int exit = exec(name, windows ? List.of("cmd", "/c", "openspec", "validate", "--specs", "--strict") : List.of("openspec", "validate", "--specs", "--strict"), root);
            return new Outcome(name, exit == 0 ? "PASSED" : "FAILED (exit " + exit + ")", since(start));
        } catch (IOException e) {
            return new Outcome(name, "SKIPPED (openspec not installed)", since(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(name, "FAILED (interrupted)", since(start));
        }
    }

    /**
     * Runs one check and returns its exit code. On GitHub Actions the output is captured, printed, and for a failing check its last lines are
     * also written as an error annotation, because annotations can be read without signing in to GitHub and the job log cannot.
     */
    private static int exec(String name, List<String> command, Path directory) throws IOException, InterruptedException {
        boolean ci = "true".equals(System.getenv("GITHUB_ACTIONS"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        if (!ci) {
            return builder.inheritIO().start().waitFor();
        }
        Path log = Files.createTempFile("verify-", ".log");
        int exit = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start().waitFor();
        List<String> lines = Files.readAllLines(log, java.nio.charset.StandardCharsets.UTF_8);
        lines.forEach(System.out::println);
        if (exit != 0) {
            String tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
            if (tail.length() > 3000) {
                tail = tail.substring(tail.length() - 3000);
            }
            String escaped = tail.replace("%", "%25").replace("\r", "").replace("\n", "%0A");
            System.out.println("::error title=%s failed (exit %d)::%s".formatted(name, exit, escaped));
        }
        return exit;
    }

    private static Duration since(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }
}
