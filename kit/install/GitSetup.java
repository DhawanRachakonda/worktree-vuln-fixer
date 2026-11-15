import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The git step of {@code appmod init}: the git readiness checks, the detected default branch, the list of branches, and the
 * confirmation that saves {@code .appmod/publish.json}. JDK only, no dependencies, no {@code package} line.
 *
 * <p><b>This is a deliberate copy.</b> The checks, their texts, the branch-name rule and the default-branch detection order are the
 * same as the engine's {@code GitPreflight}, {@code BranchNames}, {@code DefaultBranch} and {@code RepositorySettings}
 * ({@code engine/src/main/java/dev/subscriber/worktree/}), because the installer cannot use engine classes. The engine's test
 * {@code GitPreflightParityTest} runs this file and the engine on the same repositories and compares them. Change one, change the other
 * (AGENTS.md, "Deliberate copies").
 *
 * <p>Run by itself for that test: {@code java install/GitSetup.java --check <repository> [--branch <name>]}; one line per check,
 * tab separated: name, status, detail, consequence.
 */
public final class GitSetup {

    record Check(String check, String status, String detail, String consequence) {
    }

    record Settings(String sourceBranch, String mode, String pullRequestCommand, String problem) {

        boolean ok() {
            return problem == null;
        }

        boolean empty() {
            return sourceBranch == null && mode == null && pullRequestCommand == null;
        }
    }

    record Found(String branch, String how) {
    }

    static final String LOCATION = ".appmod/publish.json";
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final Pattern USER_INFO = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final int GIT_SECONDS = 20;

    private GitSetup() {
    }

    public static void main(String[] args) throws IOException {
        Path repository = null;
        String branch = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> repository = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--branch" -> branch = args[++i];
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (repository == null) {
            throw new IllegalArgumentException("pass --check <repository>");
        }
        for (Check c : checks(repository, branch, false)) {
            System.out.println(String.join("\t", c.check(), c.status(), c.detail(), c.consequence()));
        }
    }

    // ---- the checks (copy of GitPreflight.run) ---------------------------------------------------------------------------

    /**
     * @param remoteWhenNone false gives the engine's answer (mode none: the remote is not looked at); true is what {@code appmod init} does, because
     *                       the person has not chosen a mode yet: the remote checks run and only warn
     */
    static List<Check> checks(Path repository, String requestedBranch, boolean remoteWhenNone) {
        List<Check> checks = new ArrayList<>();
        Check installed = gitInstalled(repository);
        checks.add(installed);
        if (installed.status().equals("FAIL")) {
            return checks;
        }
        Settings saved = readSettings(repository);
        String mode = saved.ok() && saved.mode() != null ? saved.mode() : "none";
        checks.add(settings(saved));
        if (mode.equals("none") && !remoteWhenNone) {
            checks.add(new Check("remote checks", "OK", "skipped: the publish mode is none, so nothing is pushed", ""));
        } else {
            try {
                Result url = git(repository, Map.of(), "remote", "get-url", "origin");
                checks.add(originCheck(url, mode));
                if (url.ok()) {
                    checks.add(reachable(repository, mode));
                    checks.add(pushLikely(repository, mode));
                }
            } catch (IOException e) {
                checks.add(new Check("remote origin", "FAIL", clean(e.getMessage()), "git could not be run for this repository"));
            }
        }
        checks.add(sourceBranch(repository, saved, requestedBranch));
        if (mode.equals("pull-request")) {
            checks.add(pullRequestProgram(saved));
        }
        return checks;
    }

    static boolean failed(List<Check> checks) {
        return checks.stream().anyMatch(c -> c.status().equals("FAIL"));
    }

    private static Check gitInstalled(Path repository) {
        try {
            Result version = git(repository, Map.of(), "--version");
            return version.ok() ? new Check("git", "OK", version.firstLine(), "")
                    : new Check("git", "FAIL", "git did not answer", "no session can create a worktree");
        } catch (IOException e) {
            return new Check("git", "FAIL", "git was not found on the PATH", "no session can create a worktree or publish a branch");
        }
    }

    private static Check settings(Settings saved) {
        if (!saved.ok()) {
            return new Check("saved settings", "FAIL", saved.problem(), "sessions for this repository end as ERROR until the file is fixed or removed");
        }
        if (saved.empty()) {
            return new Check("saved settings", "OK", "none saved (the engine's defaults apply)", "");
        }
        return new Check("saved settings", "OK", "source branch %s, mode %s".formatted(saved.sourceBranch() == null ? "(not set)" : saved.sourceBranch(),
                saved.mode() == null ? "(engine's)" : saved.mode()), "");
    }

    private static Check originCheck(Result url, String mode) {
        if (url.ok()) {
            return new Check("remote origin", "OK", clean(url.firstLine()), "");
        }
        return new Check("remote origin", severity(mode), "this repository has no remote named origin",
                mode.equals("none") ? "fine while the publish mode is none; push and pull-request modes need it"
                        : "the branch of a finished session cannot be pushed; it stays a local branch");
    }

    private static Check reachable(Path repository, String mode) {
        try {
            Result listed = git(repository, Map.of("GCM_INTERACTIVE", "never"), "ls-remote", "--heads", "origin");
            if (listed.ok()) {
                return new Check("remote reachable", "OK", "origin answered with the credentials git has", "");
            }
            return new Check("remote reachable", severity(mode), reason(listed),
                    mode.equals("none") ? "matters when the publish mode is push or pull-request"
                            : "the branch cannot be pushed until the host is reachable and git has credentials for it");
        } catch (IOException e) {
            return new Check("remote reachable", severity(mode), clean(e.getMessage()),
                    mode.equals("none") ? "matters when the publish mode is push or pull-request" : "the branch cannot be pushed until the host answers in time");
        }
    }

    private static Check pushLikely(Path repository, String mode) {
        try {
            Result dry = git(repository, Map.of("GCM_INTERACTIVE", "never"), "push", "--dry-run", "origin", "HEAD:refs/heads/appmod/preflight-probe");
            if (dry.ok()) {
                return new Check("push allowed", "OK", "likely: a dry run was accepted (nothing was sent; a read-only credential can still fail later)", "");
            }
            return new Check("push allowed", severity(mode), reason(dry),
                    mode.equals("none") ? "matters when the publish mode is push or pull-request" : "the push of a session's branch is likely to be refused");
        } catch (IOException e) {
            return new Check("push allowed", severity(mode), clean(e.getMessage()), "the push could not be checked in time");
        }
    }

    private static Check sourceBranch(Path repository, Settings saved, String requested) {
        String branch = requested != null ? requested : saved.ok() ? saved.sourceBranch() : null;
        String how = requested != null ? "given" : "saved";
        try {
            if (branch == null) {
                Found found = detectDefault(repository);
                branch = found.branch();
                how = found.how();
                if (branch == null) {
                    return new Check("source branch", "WARN", "no default branch could be found (detached HEAD)", "sessions start from HEAD of the checkout");
                }
            }
            String problem = branchProblem(branch);
            if (problem != null) {
                return new Check("source branch", "FAIL", "%s is not usable: %s".formatted(branch, problem), "sessions for it end as ERROR");
            }
            String tip = tip(repository, branch);
            return tip == null
                    ? new Check("source branch", "FAIL", "%s (%s) does not exist locally or as origin/%s".formatted(branch, how, branch),
                            "a session for it ends as ERROR before any work is done")
                    : new Check("source branch", "OK", "%s (%s), tip %s".formatted(branch, how, tip.substring(0, 7)), "");
        } catch (IOException e) {
            return new Check("source branch", "FAIL", clean(e.getMessage()), "git could not be run");
        }
    }

    private static Check pullRequestProgram(Settings saved) {
        String template = saved.ok() ? saved.pullRequestCommand() : null;
        if (template == null) {
            return new Check("pull-request command", "FAIL", "mode is pull-request but no command is configured",
                    "the branch is pushed, but nobody opens the pull request; you do it by hand");
        }
        String program = template.strip().split("\\s+")[0].replace("\"", "").replace("'", "");
        return onPath(program).isPresent()
                ? new Check("pull-request command", "OK", "will run %s".formatted(onPath(program).get()), "")
                : new Check("pull-request command", "FAIL", "the program %s is not on the PATH".formatted(program),
                        "the branch is pushed, but no pull request is opened");
    }

    private static String severity(String mode) {
        return mode.equals("none") ? "WARN" : "FAIL";
    }

    private static String reason(Result result) {
        String line = result.output().lines().map(String::strip).filter(l -> l.startsWith("fatal:") || l.startsWith("error:") || l.startsWith("remote:"))
                .findFirst().orElse(result.firstLine());
        return clean(line.isEmpty() ? "git exited with code " + result.exitCode() : line);
    }

    private static Optional<Path> onPath(String program) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> names = new ArrayList<>(List.of(program));
        if (windows) {
            for (String extension : (System.getenv("PATHEXT") == null ? ".COM;.EXE;.BAT;.CMD" : System.getenv("PATHEXT")).split(";")) {
                if (!extension.isBlank()) {
                    names.add(program + extension.strip());
                }
            }
        }
        for (String directory : (System.getenv("PATH") == null ? "" : System.getenv("PATH")).split(java.io.File.pathSeparator)) {
            for (String name : names) {
                try {
                    Path candidate = Path.of(directory.strip().replace("\"", "")).resolve(name);
                    if (!directory.isBlank() && Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (RuntimeException _) {
                    // a PATH entry that is not a path cannot hold the program
                }
            }
        }
        return Optional.empty();
    }

    // ---- default branch (copy of DefaultBranch.detect) -------------------------------------------------------------------

    static Found detectDefault(Path repository) throws IOException {
        boolean hasOrigin = git(repository, Map.of(), "remote", "get-url", "origin").ok();
        Result symbolic = git(repository, Map.of(), "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        if (symbolic.ok() && symbolic.firstLine().startsWith("origin/")) {
            String name = symbolic.firstLine().substring("origin/".length());
            if (branchProblem(name) == null) {
                return new Found(name, "remote default");
            }
        }
        if (hasOrigin) {
            try {
                Result asked = git(repository, Map.of(), "ls-remote", "--symref", "origin", "HEAD");
                if (asked.ok()) {
                    for (String line : asked.output().lines().toList()) {
                        if (line.startsWith("ref: refs/heads/")) {
                            String name = line.substring("ref: refs/heads/".length()).split("\\s+")[0];
                            if (branchProblem(name) == null) {
                                return new Found(name, "remote default");
                            }
                        }
                    }
                }
            } catch (IOException _) {
                // fall through to the local guesses
            }
        }
        for (String candidate : new String[] {"main", "master"}) {
            if (git(repository, Map.of(), "rev-parse", "--verify", "--quiet", "refs/heads/" + candidate).ok()) {
                return new Found(candidate, "local guess");
            }
        }
        return new Found(currentBranch(repository), hasOrigin ? "local guess" : "no remote");
    }

    static String currentBranch(Path repository) throws IOException {
        Result head = git(repository, Map.of(), "symbolic-ref", "--quiet", "--short", "HEAD");
        return head.ok() && branchProblem(head.firstLine()) == null ? head.firstLine() : null;
    }

    /** The branches of the repository, local ones first, for the person to choose from. */
    static List<String> branches(Path repository) throws IOException {
        List<String> names = new ArrayList<>();
        for (String ref : new String[] {"refs/heads", "refs/remotes/origin"}) {
            Result listed = git(repository, Map.of(), "for-each-ref", "--format=%(refname:short)", ref);
            if (listed.ok()) {
                listed.output().lines().map(String::strip).filter(n -> !n.isEmpty() && !n.equals("origin") && !n.equals("origin/HEAD"))
                        .map(n -> n.startsWith("origin/") ? n.substring("origin/".length()) : n).filter(n -> !names.contains(n)).forEach(names::add);
            }
        }
        return names;
    }

    static String tip(Path repository, String branch) throws IOException {
        for (String ref : new String[] {"refs/heads/" + branch, "refs/remotes/origin/" + branch}) {
            Result result = git(repository, Map.of(), "rev-parse", "--verify", "--quiet", "--end-of-options", ref + "^{commit}");
            String full = result.output().strip();
            if (result.ok() && FULL_COMMIT.matcher(full).matches()) {
                return full;
            }
        }
        return null;
    }

    // ---- branch names (copy of BranchNames) ------------------------------------------------------------------------------

    static String branchProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "the branch name is empty";
        }
        if (name.length() > 200) {
            return "the branch name is longer than 200 characters";
        }
        if (!ALLOWED.matcher(name).matches()) {
            return "a branch name may only contain letters, digits and . _ / -";
        }
        if (name.startsWith("-") || name.startsWith("/") || name.contains("..") || name.contains("//") || name.endsWith("/")
                || name.endsWith(".") || name.endsWith(".lock") || name.contains("/.") || name.startsWith(".")) {
            return "that is not a valid git branch name";
        }
        return null;
    }

    // ---- .appmod/publish.json (copy of RepositorySettings, for flat objects of text) --------------------------------------

    static Settings readSettings(Path repository) {
        Path file = repository.resolve(LOCATION);
        if (!Files.exists(file)) {
            return new Settings(null, null, null, null);
        }
        if (!Files.isRegularFile(file)) {
            return bad("%s is not a file".formatted(LOCATION));
        }
        Map<String, Object> values;
        try {
            values = FlatJson.parse(Files.readString(file));
        } catch (FlatJson.NotAnObject e) {
            return bad("%s must hold a JSON object".formatted(LOCATION));
        } catch (IOException | RuntimeException _) {
            return bad("%s is not valid JSON".formatted(LOCATION));
        }
        for (String key : values.keySet()) {
            if (!List.of("sourceBranch", "mode", "pullRequestCommand").contains(key)) {
                return bad("%s has the unknown key \"%s\" (known: sourceBranch, mode, pullRequestCommand)".formatted(LOCATION, key));
            }
        }
        for (Object value : values.values()) {
            if (value != null && !(value instanceof String)) {
                return bad("%s: sourceBranch, mode and pullRequestCommand must be text".formatted(LOCATION));
            }
        }
        String branch = blank((String) values.get("sourceBranch"));
        String mode = blank((String) values.get("mode"));
        String command = blank((String) values.get("pullRequestCommand"));
        if (branch != null && branchProblem(branch) != null) {
            return bad("%s: sourceBranch \"%s\" is not usable: %s".formatted(LOCATION, printable(branch), branchProblem(branch)));
        }
        if (mode != null) {
            mode = mode.toLowerCase(Locale.ROOT);
            if (!List.of("none", "push", "pull-request").contains(mode)) {
                return bad("%s: mode must be none, push or pull-request".formatted(LOCATION));
            }
        }
        if (command != null && (command.length() > 500 || command.chars().anyMatch(c -> c < 32))) {
            return bad("%s: pullRequestCommand must be one line of at most 500 characters".formatted(LOCATION));
        }
        return new Settings(branch, mode, command, null);
    }

    private static Settings bad(String problem) {
        return new Settings(null, null, null, problem);
    }

    private static String blank(String value) {
        return value == null || value.strip().isEmpty() ? null : value.strip();
    }

    private static String printable(String text) {
        String cleaned = text.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) + "..." : cleaned;
    }

    /** Saves the source branch; the only file the installer writes besides the kit. Keeps any other key already in the file. */
    static void saveSourceBranch(Path repository, String branch) throws IOException {
        Path file = repository.resolve(LOCATION);
        Settings existing = readSettings(repository);
        Files.createDirectories(file.getParent());
        StringBuilder json = new StringBuilder("{\n  \"sourceBranch\": ").append(FlatJson.quote(branch));
        if (existing.ok() && existing.mode() != null) {
            json.append(",\n  \"mode\": ").append(FlatJson.quote(existing.mode()));
        }
        if (existing.ok() && existing.pullRequestCommand() != null) {
            json.append(",\n  \"pullRequestCommand\": ").append(FlatJson.quote(existing.pullRequestCommand()));
        }
        Files.writeString(file, json.append("\n}\n").toString(), StandardCharsets.UTF_8);
    }

    // ---- the interactive step ---------------------------------------------------------------------------------------------

    /**
     * What {@code appmod init} does about git for a repository. Never throws and never fails the install: every problem is a line of
     * output. {@code answers} is where the person's answer is read from (a terminal, or a file for the tests); null means there is no
     * terminal, so nothing is saved.
     *
     * @return the lines to print
     */
    static void init(Path repository, BufferedReader answers, boolean keepSaved, java.util.function.Consumer<String> say) {

        try {
            if (!Files.exists(repository.resolve(".git"))) {
                say.accept("git: %s is not a git repository, so the git checks and the source branch were skipped.".formatted(repository));
                return;
            }
            say.accept("Git checks for this repository (nothing is fetched or pushed):");
            List<Check> checks = checks(repository, null, true);
            for (Check c : checks) {
                say.accept("  [%s] %s: %s".formatted(switch (c.status()) {
                    case "OK" -> "ok";
                    case "WARN" -> "warning";
                    default -> "FAILED";
                }, c.check(), c.detail()));
                if (!c.consequence().isEmpty()) {
                    say.accept("          Consequence: " + c.consequence());
                }
            }
            if (failed(checks)) {
                say.accept("  The kit is installed anyway. Fix the failed checks before you turn publishing on.");
            }
            Found found = detectDefault(repository);
            Settings saved = readSettings(repository);
            if (keepSaved) {
                say.accept("Source branch: the saved setting is kept (--upgrade does not ask).");
                return;
            }
            if (saved.ok() && saved.sourceBranch() != null) {
                say.accept("Source branch: %s is already saved in %s and is kept.".formatted(saved.sourceBranch(), LOCATION));
                return;
            }
            String shown = found.branch() == null ? "(none found: HEAD of the checkout)" : "%s (%s)".formatted(found.branch(), found.how());
            say.accept("Detected default branch: " + shown);
            List<String> branches = branches(repository);
            if (!branches.isEmpty()) {
                say.accept("Branches: " + String.join(", ", branches.subList(0, Math.min(30, branches.size()))) + (branches.size() > 30 ? ", ..." : ""));
            }
            if (answers == null) {
                say.accept("No terminal: nothing was saved. Sessions will start from %s unless a branch is given with publish --source-branch, or saved in %s."
                        .formatted(found.branch() == null ? "HEAD" : found.branch(), LOCATION));
                return;
            }
            System.out.print("Source branch for fix sessions [%s] (Enter keeps the detected one, a name chooses another): "
                    .formatted(found.branch() == null ? "none" : found.branch()));
            System.out.flush();
            String line = answers.readLine();
            String chosen = line == null || line.isBlank() ? found.branch() : line.strip();
            if (chosen == null) {
                say.accept("Nothing was saved.");
                return;
            }
            String problem = branchProblem(chosen);
            if (problem == null && tip(repository, chosen) == null) {
                problem = "it does not exist locally or as origin/" + chosen;
            }
            if (problem != null) {
                say.accept("Not saved: %s is not usable: %s. Run appmod init again, or write %s yourself.".formatted(printable(chosen), problem, LOCATION));
                return;
            }
            saveSourceBranch(repository, chosen);
            say.accept("Saved source branch %s in %s (this file is not committed).".formatted(chosen, LOCATION));
        } catch (IOException | RuntimeException e) {
            say.accept("git: the git step could not finish (%s); the kit is installed.".formatted(clean(String.valueOf(e.getMessage()))));
        }
        return;
    }

    /** A reader on the terminal when there is one, else on the file named by {@code -Dappmod.init.answers} (used by the tests), else null. */
    static BufferedReader answerSource() {
        String file = System.getProperty("appmod.init.answers");
        try {
            if (file != null) {
                return Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8);
            }
        } catch (IOException _) {
            return null;
        }
        var console = System.console();
        return console != null && console.isTerminal() ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
    }

    // ---- git, without a shell, a prompt or an unbounded wait (copy of GitRunner) -------------------------------------------

    record Result(int exitCode, String output) {

        boolean ok() {
            return exitCode == 0;
        }

        String firstLine() {
            String text = output.strip();
            int end = text.indexOf('\n');
            return (end < 0 ? text : text.substring(0, end)).strip();
        }
    }

    static Result git(Path folder, Map<String, String> environment, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.askPass="));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR", "GIT_ASKPASS",
                "SSH_ASKPASS", "GCM_INTERACTIVE").contains(key.toUpperCase(Locale.ROOT)));
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().putAll(environment);
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
            } catch (IOException _) {
                return "";
            }
        });
        try {
            String text = output.get(GIT_SECONDS, TimeUnit.SECONDS);
            if (!process.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) {
                kill(process);
                throw new IOException("git did not finish in %d seconds".formatted(GIT_SECONDS));
            }
            return new Result(process.exitValue(), text);
        } catch (java.util.concurrent.TimeoutException _) {
            kill(process);
            throw new IOException("git did not finish in %d seconds".formatted(GIT_SECONDS));
        } catch (InterruptedException _) {
            kill(process);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for git");
        } catch (java.util.concurrent.ExecutionException _) {
            kill(process);
            throw new IOException("git output could not be read");
        }
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /** Credentials in a URL and control characters are removed before text is shown. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        return USER_INFO.matcher(text).replaceAll("$1***@").replaceAll("\\p{Cntrl}", " ").strip();
    }

    /** A strict reader for an object whose values are text or null, which is all {@code publish.json} holds. */
    static final class FlatJson {

        static final class NotAnObject extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        private final String text;
        private int at;

        private FlatJson(String text) {
            this.text = text;
        }

        static Map<String, Object> parse(String text) {
            FlatJson p = new FlatJson(text);
            p.skip();
            if (p.at >= text.length() || text.charAt(p.at) != '{') {
                if (p.at < text.length() && (text.charAt(p.at) == '[' || text.charAt(p.at) == '"' || Character.isDigit(text.charAt(p.at)))) {
                    throw new NotAnObject();
                }
                throw new IllegalArgumentException("not JSON");
            }
            p.at++;
            Map<String, Object> values = new java.util.LinkedHashMap<>();
            p.skip();
            if (p.peek() == '}') {
                p.at++;
            } else {
                while (true) {
                    p.skip();
                    String key = p.string();
                    p.skip();
                    p.expect(':');
                    p.skip();
                    values.put(key, p.value());
                    p.skip();
                    if (p.peek() == ',') {
                        p.at++;
                        continue;
                    }
                    p.expect('}');
                    break;
                }
            }
            p.skip();
            if (p.at != text.length()) {
                throw new IllegalArgumentException("trailing text");
            }
            return values;
        }

        private Object value() {
            char c = peek();
            if (c == '"') {
                return string();
            }
            for (String word : new String[] {"null", "true", "false"}) {
                if (text.startsWith(word, at)) {
                    at += word.length();
                    return word.equals("null") ? null : Boolean.valueOf(word);
                }
            }
            int start = at;
            while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            if (start == at) {
                throw new IllegalArgumentException("a value of a kind publish.json does not hold");
            }
            return Double.valueOf(text.substring(start, at));
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 32) {
                    throw new IllegalArgumentException("control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("bad escape");
                }
            }
        }

        private char peek() {
            return at < text.length() ? text.charAt(at) : 0;
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected " + c);
            }
            at++;
        }

        private void skip() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        static String quote(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 32) {
                            out.append("\\u%04x".formatted((int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            return out.append('"').toString();
        }
    }
}
