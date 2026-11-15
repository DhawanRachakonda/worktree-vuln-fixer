import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The git step of {@code appmod init} (KitInstaller + GitSetup): detected default branch, branch list, the confirmation that saves
 * {@code .appmod/publish.json}, and what happens without a terminal, with an unreachable remote, with --upgrade, with bad answers.
 * Single file, JDK only; the installer runs as a separate process into temporary folders, as users run it.
 *
 * <pre>
 *   java tests/installer/GitStepTests.java [kit-root]        (kit-root defaults to the current directory)
 * </pre>
 *
 * Exit code 0 when every check passes, 1 otherwise.
 */
public final class GitStepTests {

    private static int passed;
    private static int failed;
    private static Path kit;
    private static Path scratch;
    private static int runs;

    record Run(int exit, String out, String err) {
    }

    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        scratch = Files.createTempDirectory("git-step-tests-");
        try {
            interactiveChoiceIsSaved();
            withoutATerminalNothingIsSaved();
            remoteUnreachableStillInstalls();
            upgradeKeepsTheSavedFile();
            badAnswersAndOtherFolders();
        } finally {
            deleteTree(scratch);
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static void interactiveChoiceIsSaved() throws Exception {
        Path repo = gitRepo("git-interactive");
        Run run = install(repo, "main");

        check("exits 0 and installs the kit", run.exit == 0 && Files.exists(repo.resolve(".github/agents")), run.err);
        check("shows the detected remote default and the branches", run.out.contains("Detected default branch: develop (remote default)")
                && run.out.contains("Branches:") && run.out.contains("develop") && run.out.contains("main"), run.out);
        check("shows the checks", run.out.contains("[ok] git:") && run.out.contains("[ok] remote reachable"), run.out);
        Path saved = repo.resolve(".appmod/publish.json");
        check("the chosen branch is saved", Files.isRegularFile(saved) && Files.readString(saved).contains("\"sourceBranch\": \"main\""),
                Files.exists(saved) ? Files.readString(saved) : "no file");
        check("the .appmod folder is ignored by git, so the setting is not committed", Files.readString(repo.resolve(".gitignore")).contains(".appmod/"));
        check("the person's files are untouched", git(repo, "status", "--porcelain").lines().noneMatch(l -> l.contains("a.txt")));

        Path accepted = gitRepo("git-accept");
        Run enter = install(accepted, "");
        check("Enter accepts the detected default", Files.exists(accepted.resolve(".appmod/publish.json"))
                && Files.readString(accepted.resolve(".appmod/publish.json")).contains("\"sourceBranch\": \"develop\""), enter.out);
    }

    static void withoutATerminalNothingIsSaved() throws Exception {
        Path repo = gitRepo("git-no-terminal");
        Run run = install(repo, null);

        check("no terminal: exits 0 and installs the kit", run.exit == 0 && Files.exists(repo.resolve(".github/agents")), run.err);
        check("no terminal: saves nothing", !Files.exists(repo.resolve(".appmod/publish.json")));
        check("no terminal: says which branch would be used", run.out.contains("No terminal: nothing was saved") && run.out.contains("start from develop"), run.out);
    }

    static void remoteUnreachableStillInstalls() throws Exception {
        Path repo = gitRepo("git-unreachable");
        git(repo, "remote", "set-url", "origin", "http://alice:s3cr3t-token@127.0.0.1:1/shop.git");
        Run run = install(repo, "main");

        check("unreachable remote: the kit is installed and the exit status is success", run.exit == 0 && Files.exists(repo.resolve(".github/agents")), run.err);
        check("unreachable remote: the warning names what is lost", run.out.contains("[warning] remote reachable")
                && run.out.contains("Consequence: matters when the publish mode is push or pull-request"), run.out);
        check("unreachable remote: the credentials in the URL are never printed", !run.out.contains("s3cr3t-token") && !run.out.contains("alice")
                && !run.err.contains("s3cr3t-token"), run.out);
        check("unreachable remote: the branch can still be saved", Files.exists(repo.resolve(".appmod/publish.json")), run.out);
    }

    static void upgradeKeepsTheSavedFile() throws Exception {
        Path repo = gitRepo("git-upgrade");
        install(repo, "main");
        Path saved = repo.resolve(".appmod/publish.json");
        Files.writeString(saved, "{\n  \"sourceBranch\": \"develop\",\n  \"mode\": \"push\"\n}\n");
        String before = Files.readString(saved);

        Run run = install(repo, "main", "--upgrade");

        check("--upgrade: exits 0", run.exit == 0, run.err + run.out);
        check("--upgrade: the saved file is untouched and no question is asked", Files.readString(saved).equals(before)
                && run.out.contains("the saved setting is kept"), run.out);
        Run again = install(repo, "main");
        check("a plain re-run keeps a saved branch and does not overwrite it", Files.readString(saved).equals(before) && again.out.contains("already saved"), again.out);
    }

    static void badAnswersAndOtherFolders() throws Exception {
        Path repo = gitRepo("git-bad-answer");
        Run bad = install(repo, "no-such-branch");
        check("a branch that does not exist is not saved and the kit is still installed", bad.exit == 0 && !Files.exists(repo.resolve(".appmod/publish.json"))
                && bad.out.contains("Not saved") && Files.exists(repo.resolve(".github/agents")), bad.out);
        Run evil = install(gitRepo("git-evil-answer"), "--upload-pack=x");
        check("an answer that is not a branch name is refused", evil.out.contains("Not saved") && evil.out.contains("not usable"), evil.out);

        Path plain = fresh("not-a-repo");
        Run notGit = install(plain, "main");
        check("a folder that is not a git repository installs and says the git step was skipped", notGit.exit == 0
                && notGit.out.contains("is not a git repository") && !Files.exists(plain.resolve(".appmod/publish.json")), notGit.out);

        Path broken = gitRepo("git-broken-setting");
        Files.createDirectories(broken.resolve(".appmod"));
        Files.writeString(broken.resolve(".appmod/publish.json"), "{nope");
        Run brokenRun = install(broken, "main");
        check("an unreadable saved setting is reported and the kit is installed", brokenRun.exit == 0 && brokenRun.out.contains("[FAILED] saved settings")
                && Files.exists(broken.resolve(".github/agents")), brokenRun.out);

        Path dry = gitRepo("git-dry");
        Run dryRun = install(dry, "main", "--dry-run");
        check("--dry-run: no git step and nothing written", !dryRun.out.contains("Git checks") && !Files.exists(dry.resolve(".appmod")), dryRun.out);
    }

    // ---- helpers ----

    static String git(Path folder, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
        }
        return out.strip();
    }

    static Path fresh(String name) throws IOException {
        return Files.createDirectories(scratch.resolve(name));
    }

    /** A repository on main with a develop branch and a bare origin whose default branch is develop. */
    static Path gitRepo(String name) throws Exception {
        Path repo = fresh(name);
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "first");
        git(repo, "branch", "develop");
        Path bare = scratch.resolve(name + "-origin.git");
        git(scratch, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "-q", "origin", "main", "develop");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/develop");
        git(repo, "fetch", "-q", "origin");
        git(repo, "remote", "set-head", "origin", "--auto");
        return repo;
    }

    /** Runs the installer on a repository; {@code answer} is what the person types (null: there is no terminal). */
    static Run install(Path repo, String answer, String... extra) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString()));
        if (answer != null) {
            Path file = scratch.resolve("answers-" + (++runs) + ".txt");
            Files.writeString(file, answer + "\n");
            command.add("-Dappmod.init.answers=" + file);
        }
        command.add(kit.resolve("install/KitInstaller.java").toString());
        command.addAll(List.of("--target", repo.toString()));
        command.addAll(List.of(extra));
        Path out = scratch.resolve("run-" + (++runs) + ".out");
        Path err = scratch.resolve("run-" + runs + ".err");
        Process p = new ProcessBuilder(command).directory(kit.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("the installer did not finish in 180 seconds");
        }
        return new Run(p.exitValue(), lenient(out), lenient(err));
    }

    static String lenient(Path file) throws IOException {
        return String.join("\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList());
    }

    static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (var paths = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                path.toFile().setWritable(true);
                Files.deleteIfExists(path);
            }
        }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "  -> " + detail.replace("\n", " | ")));
        }
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
    }
}
