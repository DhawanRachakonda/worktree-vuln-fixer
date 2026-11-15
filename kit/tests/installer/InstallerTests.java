import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks for KitInstaller.java and what it places for GitHub Copilot and Claude Code. Single file, JDK only; the installer runs as a
 * separate process, as users run it, into temporary folders.
 *
 * <pre>
 *   java tests/installer/InstallerTests.java [kit-root]        (kit-root defaults to the current directory)
 * </pre>
 *
 * Exit code 0 when every check passes, 1 otherwise.
 */
public final class InstallerTests {

    private static int passed;
    private static int failed;
    private static Path kit;
    private static Path scratch;
    private static int runs;

    record Run(int exit, String out, String err) {
    }

    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        scratch = Files.createTempDirectory("installer-tests-");
        try {
            defaultIsCopilot();
            unknownTool();
            claudeAgents();
            frontDoorLeftOut();
            unknownToolName();
            mcpToolNames();
            skillsRulesAndMemory();
            existingMemoryFile();
            bothTools();
            secondRun();
            editedFile();
            dryRun();
            mcp();
            userInstall();
            claudeOnlyAuditValidation();
            recordOfTheInstall();
            upgradeNeedsARecord();
            upgradeKeepsEditedFiles();
            upgradeRefusals();
        } finally {
            deleteTree(scratch);
        }
        System.out.printf("%n%d passed, %d failed%n", passed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- scenarios ----

    static void defaultIsCopilot() throws Exception {
        Path plain = fresh("default");
        Path explicit = fresh("copilot");
        install(kit, "--target", plain.toString());
        install(kit, "--target", explicit.toString(), "--tool", "copilot");

        check("default: nothing under .claude and no CLAUDE.md", !Files.exists(plain.resolve(".claude")) && !Files.exists(plain.resolve("CLAUDE.md")));
        check("default: the same files and bytes as --tool copilot", listing(plain).equals(listing(explicit)));
        List<String> mismatches = new ArrayList<>();
        for (String sub : List.of("agents", "skills", "instructions", "prompts", "modernize/rulebook", "appmod/tools")) {
            Path from = kit.resolve(".github").resolve(sub);
            try (Stream<Path> files = Files.walk(from)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    Path copy = plain.resolve(".github").resolve(sub).resolve(from.relativize(f).toString());
                    if (!Files.exists(copy) || Files.mismatch(f, copy) != -1) {
                        mismatches.add(copy.toString());
                    }
                }
            }
        }
        check("default: every .github source file is placed byte for byte", mismatches.isEmpty(), String.join(", ", mismatches));
        String instructions = read(plain.resolve(".github/copilot-instructions.md"));
        check("default: copilot-instructions.md holds the kit block", instructions.contains("<!-- appmod-kit:start -->") && instructions.contains("appmod-harness"));
    }

    static void unknownTool() throws Exception {
        Path target = fresh("unknown-tool");
        Run run = installRaw(kit, "--target", target.toString(), "--tool", "vim");
        check("unknown tool: exit code 2", run.exit == 2, "exit " + run.exit);
        check("unknown tool: names the allowed values", run.err.contains("copilot, claude or both"), run.err);
        check("unknown tool: nothing was written", listing(target).isEmpty());
        Run missing = installRaw(kit, "--target", target.toString(), "--tool");
        check("--tool without a value is refused too", missing.exit == 2 && listing(target).isEmpty());
    }

    static void claudeAgents() throws Exception {
        Path target = fresh("claude");
        install(kit, "--target", target.toString(), "--tool", "claude");

        String fixer = read(target.resolve(".claude/agents/security-fixer.md"));
        String source = read(kit.resolve(".github/agents/security-fixer.agent.md")).replace("\r\n", "\n");
        check("claude: security-fixer has the tools mapped", fixer.contains("\ntools: Read, Grep, Glob, Edit, Write, Bash, PowerShell, Agent\n"));
        check("claude: no user-invocable key", !fixer.contains("user-invocable"));
        check("claude: same name and description line", fixer.contains("name: security-fixer") && fixer.contains(line(source, "description:")));
        String sourceBody = source.substring(source.indexOf("\n---\n", 3) + 5);
        String fixerBody = fixer.substring(fixer.indexOf("\n---\n", 3) + 5);
        check("claude: the body is the source's apart from the rewritten paths",
                fixerBody.equals(sourceBody.replace(".github/skills/", ".claude/skills/").replace(".github/agents/", ".claude/agents/")
                        .replace(".github/instructions/appmod-harness.instructions.md", ".claude/rules/appmod-harness.md")));
        check("claude: the tools of the Java tools stay at .github/appmod/tools", fixerBody.contains(".github/appmod/tools/IssueList.java"));
        String verifier = read(target.resolve(".claude/agents/appmod-verifier.md"));
        check("claude: a read-only agent gets no edit tools", verifier.contains("\ntools: Read, Grep, Glob, Bash, PowerShell\n")
                && !verifier.contains("Edit"));
        String audit = read(target.resolve(".claude/agents/security-audit.md"));
        check("claude: the audit agent can call the intel service's tool (an agent with a tool list cannot use an MCP tool that is not named)",
                audit.contains("\ntools: Read, Grep, Glob, Edit, Write, Bash, PowerShell, Agent, mcp__intel__listVulnerabilities\n"));
        check("claude: nothing under .github/agents or prompts", !Files.exists(target.resolve(".github/agents")) && !Files.exists(target.resolve(".github/prompts"))
                && !Files.exists(target.resolve(".github/copilot-instructions.md")));
    }

    static void frontDoorLeftOut() throws Exception {
        Path target = fresh("front-door");
        Run run = install(kit, "--target", target.toString(), "--tool", "claude");

        check("front door: no appmod-harness agent for Claude Code", !Files.exists(target.resolve(".claude/agents/appmod-harness.md")));
        check("front door: the report says why", run.out.contains("skip (Copilot only)") && run.out.contains("appmod-harness")
                && run.out.contains("github-copilot-modernization:modernize"), run.out);
    }

    static void mcpToolNames() throws Exception {
        Path fixtureKit = copyKit("fixture-kit-mcp");
        Path agent = fixtureKit.resolve(".github/agents/appmod-verifier.agent.md");
        String source = read(agent);
        int start = source.indexOf("tools: [");
        int end = source.indexOf("]", start) + 1;
        Files.writeString(agent, source.substring(0, start) + "tools: [\"read\", \"other/*\", \"other/tool-one\", \"other/tool-one\"]" + source.substring(end),
                StandardCharsets.UTF_8);
        Path target = fresh("mcp-tool-names");
        Run run = installRaw(fixtureKit, "--target", target.toString(), "--tool", "claude");

        check("mcp tools: the install works", run.exit == 0, run.err);
        String verifier = read(target.resolve(".claude/agents/appmod-verifier.md"));
        check("mcp tools: server/* becomes mcp__server, server/tool becomes mcp__server__tool, once", verifier.contains("\ntools: Read, mcp__other, mcp__other__tool-one\n"),
                verifier.substring(0, Math.min(400, verifier.length())));
    }

    static void unknownToolName() throws Exception {
        Path fixtureKit = copyKit("fixture-kit");
        Path agent = fixtureKit.resolve(".github/agents/security-fixer.agent.md");
        Files.writeString(agent, read(agent).replace("tools: [\"read\", \"search\", \"edit\", \"shell\", \"agent\"]",
                "tools: [\"read\", \"teleport\"]"), StandardCharsets.UTF_8);
        Path target = fresh("unknown-tool-name");
        Run run = installRaw(fixtureKit, "--target", target.toString(), "--tool", "claude");

        check("unknown tool name: the install fails", run.exit == 1, "exit " + run.exit);
        check("unknown tool name: names the agent and the tool", run.err.contains("security-fixer") && run.err.contains("teleport"), run.err);
        check("unknown tool name: nothing was written for Claude Code", listing(target).isEmpty(), listing(target).keySet().toString());
        Run copilot = installRaw(fixtureKit, "--target", fresh("unknown-tool-name-copilot").toString());
        check("unknown tool name: Copilot installs do not care", copilot.exit == 0);
    }

    static void skillsRulesAndMemory() throws Exception {
        Path target = fresh("skills");
        install(kit, "--target", target.toString(), "--tool", "claude");

        check("skills: the security-audit skill is in .claude/skills", Files.isRegularFile(target.resolve(".claude/skills/security-audit/SKILL.md")));
        List<String> sourceSkills = names(kit.resolve(".github/skills"));
        check("skills: every kit skill is placed", names(target.resolve(".claude/skills")).equals(sourceSkills), sourceSkills.toString());
        check("skills: a validator is copied byte for byte, with the shared Json.java beside it", sameBytes(kit.resolve(".github/skills/security-audit/ValidateFindings.java"),
                target.resolve(".claude/skills/security-audit/ValidateFindings.java"))
                && sameBytes(kit.resolve(".github/skills/security-audit/Json.java"), target.resolve(".claude/skills/security-audit/Json.java")));
        check("rules: harness rules have no path restriction", read(target.resolve(".claude/rules/appmod-harness.md")).startsWith("\n# Modernization ground rules")
                || read(target.resolve(".claude/rules/appmod-harness.md")).startsWith("# Modernization ground rules"));
        check("rules: sql rules are restricted to sql paths", read(target.resolve(".claude/rules/sqlserver.md"))
                .startsWith("---\npaths:\n  - \"**/*.sql\"\n  - \"**/*.sqlproj\"\n---\n"));
        check("tools: the Java tools and the rulebook are installed", Files.isRegularFile(target.resolve(".github/appmod/tools/IssueList.java"))
                && Files.isRegularFile(target.resolve(".github/modernize/rulebook/10-security-guardrails.md")));
        String memory = read(target.resolve("CLAUDE.md"));
        check("memory: CLAUDE.md names the agents and is marked", memory.contains("<!-- appmod-kit:start -->") && memory.contains("`security-fixer`")
                && !memory.contains("`appmod-harness`, "));

        List<String> stale = new ArrayList<>();
        try (Stream<Path> files = Files.walk(target)) {
            for (Path f : files.filter(Files::isRegularFile).filter(p -> p.toString().replace('\\', '/').matches(".*/(\\.claude/.*|CLAUDE\\.md|\\.mcp\\.json)$")).toList()) {
                String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                if (f.getFileName().toString().matches(".*\\.(md|json)$")
                        && (text.contains(".github/agents/") || text.contains(".github/skills/") || text.contains(".github/instructions/"))) {
                    stale.add(target.relativize(f).toString());
                }
            }
        }
        check("stale paths: no Claude Code file refers to .github/agents, skills or instructions", stale.isEmpty(), stale.toString());
    }

    static void existingMemoryFile() throws Exception {
        Path target = fresh("memory");
        Files.writeString(target.resolve("CLAUDE.md"), "# My project\n\nKeep this.\n");
        install(kit, "--target", target.toString(), "--tool", "claude");
        String once = read(target.resolve("CLAUDE.md"));
        check("existing CLAUDE.md: the content is kept and the block appended", once.startsWith("# My project\n\nKeep this.\n") && once.contains("<!-- appmod-kit:start -->"));

        Run again = install(kit, "--target", target.toString(), "--tool", "claude");
        String twice = read(target.resolve("CLAUDE.md"));
        check("existing CLAUDE.md: a second run appends nothing", twice.equals(once) && again.out.contains("skip (merged)"));
    }

    static void bothTools() throws Exception {
        Path target = fresh("both");
        install(kit, "--target", target.toString(), "--tool", "both");

        check("both: the Copilot files are there", Files.isRegularFile(target.resolve(".github/agents/appmod-harness.agent.md"))
                && Files.isRegularFile(target.resolve(".github/copilot-instructions.md")) && Files.isDirectory(target.resolve(".github/prompts")));
        check("both: the Claude Code files are there", Files.isRegularFile(target.resolve(".claude/agents/security-fixer.md"))
                && Files.isRegularFile(target.resolve("CLAUDE.md")));
        check("both: the Copilot agent file is still the source's", sameBytes(kit.resolve(".github/agents/security-fixer.agent.md"),
                target.resolve(".github/agents/security-fixer.agent.md")));
    }

    static void secondRun() throws Exception {
        Path target = fresh("second");
        install(kit, "--target", target.toString(), "--tool", "both", "--with-mcp");
        Map<String, String> before = listing(target);
        Run again = install(kit, "--target", target.toString(), "--tool", "both", "--with-mcp");

        check("second run: nothing changed", listing(target).equals(before));
        check("second run: every line is unchanged, merged or a Copilot-only note", again.out.lines()
                .filter(l -> l.matches("^(create|overwrite|append|skip \\(exists\\)).*")).filter(l -> !l.startsWith("append") || !l.contains(".gitignore")).toList().isEmpty(),
                again.out.lines().filter(l -> l.startsWith("create") || l.startsWith("overwrite")).findFirst().orElse(""));
    }

    static void editedFile() throws Exception {
        Path target = fresh("edited");
        install(kit, "--target", target.toString(), "--tool", "claude");
        Path agent = target.resolve(".claude/agents/security-fixer.md");
        Files.writeString(agent, read(agent) + "\nMy own rule.\n");
        Run kept = install(kit, "--target", target.toString(), "--tool", "claude");

        check("edited file: kept without --force", read(agent).endsWith("\nMy own rule.\n"));
        check("edited file: the report says it was skipped", kept.out.contains("skip (exists)") && kept.out.contains("security-fixer.md"));
        install(kit, "--target", target.toString(), "--tool", "claude", "--force");
        check("edited file: --force restores the rendered file", !read(agent).contains("My own rule."));
    }

    static void dryRun() throws Exception {
        Path target = fresh("dry");
        Run run = install(kit, "--target", target.toString(), "--tool", "both", "--dry-run", "--with-mcp");

        check("dry run: nothing is written", listing(target).isEmpty());
        check("dry run: the report lists what would be created", run.out.contains("create") && run.out.contains("security-fixer.md")
                && run.out.contains("(dry run - nothing written)"));
    }

    static void mcp() throws Exception {
        Path target = fresh("mcp");
        install(kit, "--target", target.toString(), "--tool", "both", "--with-mcp");

        String claude = read(target.resolve(".mcp.json"));
        String copilot = read(target.resolve(".vscode/mcp.json"));
        String pinned = "@microsoft/github-copilot-app-modernization-mcp-server@1.24.0";
        check("mcp: both files name the same server package and version", claude.contains(pinned) && copilot.contains(pinned));
        check("mcp: .mcp.json has the Claude Code shape", claude.contains("\"mcpServers\"") && claude.contains("\"command\": \"npx\""));
        Path plain = fresh("mcp-not-asked");
        install(kit, "--target", plain.toString(), "--tool", "both");
        check("mcp: nothing without --with-mcp", !Files.exists(plain.resolve(".mcp.json")) && !Files.exists(plain.resolve(".vscode/mcp.json")));
    }

    static void userInstall() throws Exception {
        Path home = fresh("home");
        Run run = runIn(kit, kit, List.of("-Duser.home=" + home), "--user", "--tool", "claude");

        check("user install: exit 0", run.exit == 0, run.err);
        check("user install: agents and skills in ~/.claude", Files.isRegularFile(home.resolve(".claude/agents/security-fixer.md"))
                && Files.isRegularFile(home.resolve(".claude/skills/security-audit/SKILL.md"))
                && Files.isRegularFile(home.resolve(".claude/appmod/tools/IssueList.java")));
        check("user install: nothing in ~/.copilot and nothing written to the kit folder", !Files.exists(home.resolve(".copilot"))
                && !Files.exists(kit.resolve(".claude")) && !Files.exists(kit.resolve("CLAUDE.md")));
        check("user install: the report says rules are repository-level", run.out.contains("repository-level"));
    }

    static String sha256(Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    static void recordOfTheInstall() throws Exception {
        Path target = fresh("record");
        install(kit, "--target", target.toString(), "--tool", "claude");
        String record = read(target.resolve(".appmod/kit.json"));
        Path fixer = target.resolve(".claude/agents/security-fixer.md");

        check("record: names the tool", record.contains("\"tool\": \"claude\""));
        check("record: holds the SHA-256 of a placed file", record.contains("\".claude/agents/security-fixer.md\": \"" + sha256(fixer) + "\""));
        check("record: lists the Java tools too", record.contains("\".github/appmod/tools/IssueList.java\": \""));
        check("record: does not list the merged CLAUDE.md or the record itself", !record.contains("CLAUDE.md") && !record.contains("kit.json"));
        check("record: is under .appmod, which the installer git-ignores", read(target.resolve(".gitignore")).contains(".appmod/"));
        Path user = fresh("record-user-home");
        runIn(kit, kit, List.of("-Duser.home=" + user), "--user", "--tool", "claude");
        check("record: a user install writes none", !Files.exists(kit.resolve(".appmod/kit.json")) && !Files.exists(user.resolve(".claude/.appmod")));
    }

    static void upgradeNeedsARecord() throws Exception {
        Path target = fresh("upgrade-none");
        Run run = installRaw(kit, "--target", target.toString(), "--upgrade");

        check("upgrade without a record: exit 2 and a message that names the record", run.exit == 2 && run.err.contains("kit.json"), run.err);
        check("upgrade without a record: nothing was written", listing(target).isEmpty());
    }

    static void upgradeKeepsEditedFiles() throws Exception {
        Path newer = copyKit("kit-v2");
        Path target = fresh("upgrade");
        install(kit, "--target", target.toString(), "--tool", "claude");
        Path fixer = target.resolve(".claude/agents/security-fixer.md");
        Path verifier = target.resolve(".claude/agents/appmod-verifier.md");
        String fixerBefore = read(fixer);
        Files.writeString(verifier, read(verifier) + "\nMy own rule.\n");

        // The kit gets a newer version of two agents.
        for (String name : List.of("security-fixer", "appmod-verifier")) {
            Path source = newer.resolve(".github/agents/" + name + ".agent.md");
            Files.writeString(source, read(source) + "\nA new sentence in version two.\n");
        }
        Run dry = runIn(newer, newer, List.of(), "--target", target.toString(), "--upgrade", "--dry-run");
        check("upgrade: a dry run says what it would do and changes nothing", dry.out.contains("upgrade") && dry.out.contains("skip (edited)")
                && read(fixer).equals(fixerBefore));
        Run up = runIn(newer, newer, List.of(), "--target", target.toString(), "--upgrade");

        check("upgrade: an untouched file gets the newer content", read(fixer).contains("A new sentence in version two.") && up.out.contains("upgrade "));
        check("upgrade: an edited file is kept and reported as edited", read(verifier).endsWith("\nMy own rule.\n") && !read(verifier).contains("version two")
                && up.out.contains("skip (edited)") && up.out.contains("appmod-verifier.md"));
        String record = read(target.resolve(".appmod/kit.json"));
        check("upgrade: the record has the new hash for the upgraded file and the old one for the edited file",
                record.contains("security-fixer.md\": \"" + sha256(fixer) + "\"") && !record.contains("appmod-verifier.md\": \"" + sha256(verifier) + "\""));

        Run again = runIn(newer, newer, List.of(), "--target", target.toString(), "--upgrade");
        check("upgrade: a second run changes nothing and still keeps the edit", again.out.lines().noneMatch(l -> l.startsWith("upgrade") || l.startsWith("create")
                || l.startsWith("overwrite")) && read(verifier).endsWith("\nMy own rule.\n"));
        runIn(newer, newer, List.of(), "--target", target.toString(), "--upgrade", "--force");
        check("upgrade: --force also replaces the edited file", !read(verifier).contains("My own rule.") && read(verifier).contains("version two"));
    }

    static void upgradeRefusals() throws Exception {
        Path target = fresh("upgrade-refusals");
        install(kit, "--target", target.toString(), "--tool", "claude");

        Run mismatch = installRaw(kit, "--target", target.toString(), "--upgrade", "--tool", "copilot");
        check("upgrade: a tool that differs from the record is refused", mismatch.exit == 2 && mismatch.err.contains("recorded tool (claude)"), mismatch.err);
        check("upgrade: the recorded tool is used when none is given", !Files.exists(target.resolve(".github/agents")) && Files.isDirectory(target.resolve(".claude/agents")));
        Run user = installRaw(kit, "--user", "--upgrade");
        check("upgrade: --user is refused", user.exit == 2 && user.err.contains("repository"), user.err);
    }

    static void claudeOnlyAuditValidation() throws Exception {
        // Found by the real Claude Code audit run: the validator's default skill folder was .github/skills, which a Claude-only install does not have.
        Path target = fresh("claude-validate");
        install(kit, "--target", target.toString(), "--tool", "claude");
        Path run = kit.resolve("tests/security-audit/fixtures/run-ok");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                target.resolve(".github/appmod/tools/ValidateRun.java").toString(), run.toString())
                .redirectErrorStream(true).redirectOutput(scratch.resolve("validate.out").toFile()).start();
        boolean done = p.waitFor(180, TimeUnit.SECONDS);
        String out = lenient(scratch.resolve("validate.out"));
        check("claude-only: ValidateRun.java finds the skill in .claude/skills without --skill-dir",
                done && p.exitValue() == 0 && out.contains("\"valid\":true") && !Files.exists(target.resolve(".github/skills")), out);
    }

    // ---- helpers ----

    static Run install(Path kitRoot, String... args) throws Exception {
        Run run = installRaw(kitRoot, args);
        if (run.exit != 0) {
            System.out.println("   (installer failed unexpectedly: " + run.err + ")");
        }
        return run;
    }

    static Run installRaw(Path kitRoot, String... args) throws Exception {
        return runIn(kitRoot, kitRoot, List.of(), args);
    }

    static Run runIn(Path cwd, Path kitRoot, List<String> jvmOptions, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(javaExecutable()));
        command.addAll(jvmOptions);
        command.add(kitRoot.resolve("install/KitInstaller.java").toString());
        command.addAll(List.of(args));
        Path out = scratch.resolve("run-" + (++runs) + ".out");
        Path err = scratch.resolve("run-" + runs + ".err");
        Process p = new ProcessBuilder(command).directory(cwd.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("the installer did not finish in 120 seconds");
        }
        // The console of the installer writes in the platform charset (the report has an en dash), so decode without failing.
        return new Run(p.exitValue(), lenient(out), lenient(err));
    }

    static String lenient(Path file) throws IOException {
        return String.join("\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8).lines().toList());
    }

    static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    static Path fresh(String name) throws IOException {
        return Files.createDirectories(scratch.resolve(name));
    }

    static Path copyKit(String name) throws IOException {
        Path to = fresh(name);
        for (String sub : List.of(".github", "install", "copilot-cli", ".vscode")) {
            copyTree(kit.resolve(sub), to.resolve(sub));
        }
        return to;
    }

    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Relative path to a hash of the content, for every file of a folder. */
    static Map<String, String> listing(Path folder) throws IOException {
        Map<String, String> map = new TreeMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                map.put(folder.relativize(f).toString().replace('\\', '/'), Integer.toHexString(java.util.Arrays.hashCode(Files.readAllBytes(f))));
            }
        }
        return map;
    }

    static List<String> names(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    static boolean sameBytes(Path a, Path b) throws IOException {
        return Files.exists(a) && Files.exists(b) && Files.mismatch(a, b) == -1;
    }

    static String line(String text, String prefix) {
        return text.lines().filter(l -> l.startsWith(prefix)).findFirst().orElse("<missing " + prefix + ">");
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    static void check(String name, boolean ok) {
        check(name, ok, "");
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

    static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
