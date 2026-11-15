import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Installs the kit (agents, skills, tools, rules). JDK 25 or later, no dependencies:
 *
 * <pre>
 *   java install/KitInstaller.java --target C:\src\my-repo            # repository install (recommended)
 *   java install/KitInstaller.java --user                              # agents + skills + tools for all repos (~/.copilot)
 *   java install/KitInstaller.java --target C:\src\my-repo --upgrade  # newer kit files, only where nobody edited the installed file
 *   options: --tool copilot|claude|both (default copilot)  --force (overwrite files that differ)  --dry-run
 *            --with-mcp (also .vscode/mcp.json for Copilot, .mcp.json for Claude Code)
 * </pre>
 *
 * <p>A repository install writes {@code .appmod/kit.json}: the tool and the SHA-256 of every file the installer placed. {@code --upgrade} reads it,
 * reuses the recorded tool, and overwrites a file only when it still has the recorded content (the person did not edit it).
 *
 * <p>Tool installation (JDK, Copilot CLI, the GitHub Copilot app modernization plugin and the Node.js that plugin needs, Maven) is done by
 * {@code appmod.cmd init <working-directory>}, which calls this class for the file part. Identical files are reported as
 * "unchanged" and never rewritten.
 *
 * <p>For GitHub Copilot, a repository install copies {@code .github/{agents,skills,instructions,prompts,modernize/rulebook,appmod/tools}} and
 * merges {@code .github/copilot-instructions.md} (appends a marked block instead of overwriting). For Claude Code the same sources are
 * rendered: {@code .claude/agents/<name>.md} (tools mapped to Claude Code's names, the Copilot-only {@code user-invocable} dropped), {@code .claude/skills},
 * {@code .claude/rules} and a marked block in {@code CLAUDE.md}. The tool-neutral {@code .github/appmod/tools} and
 * {@code .github/modernize/rulebook} are installed for either tool. The front door {@code appmod-harness} needs the Copilot-only
 * modernization plugin and is not rendered for Claude Code; the prompt files are not rendered either.
 */
public class KitInstaller {

    static final String MARK_START = "<!-- appmod-kit:start -->";
    static final String MARK_END = "<!-- appmod-kit:end -->";

    /** Agents that cannot work in Claude Code, with the reason that goes into the report. */
    static final Map<String, String> COPILOT_ONLY_AGENTS = Map.of(
            "appmod-harness", "it delegates to the GitHub Copilot-only plugin github-copilot-modernization:modernize");

    /** Copilot's tool aliases and the Claude Code tools they stand for. */
    static final Map<String, List<String>> CLAUDE_TOOLS = Map.of(
            "read", List.of("Read"),
            "search", List.of("Grep", "Glob"),
            "edit", List.of("Edit", "Write"),
            "shell", List.of("Bash", "PowerShell"),
            "agent", List.of("Agent"));

    /** The kit's own paths as they appear in text, and where the same file is for Claude Code. */
    static final Map<String, String> CLAUDE_PATHS = new LinkedHashMap<>();

    static {
        CLAUDE_PATHS.put(".github/instructions/appmod-harness.instructions.md", ".claude/rules/appmod-harness.md");
        CLAUDE_PATHS.put(".github/instructions/sqlserver.instructions.md", ".claude/rules/sqlserver.md");
        CLAUDE_PATHS.put(".github/agents/", ".claude/agents/");
        CLAUDE_PATHS.put(".github/skills/", ".claude/skills/");
    }

    static final Pattern AGENT_KEY = Pattern.compile("^([A-Za-z][A-Za-z-]*):\\s*(.*)$");
    static final Pattern MCP_TOOL = Pattern.compile("([A-Za-z0-9_-]+)/([A-Za-z0-9_*-]+)");
    static final Pattern MCP_VERSION = Pattern.compile("@microsoft/github-copilot-app-modernization-mcp-server@([0-9][0-9A-Za-z.-]*)");

    static boolean force;
    static boolean dryRun;
    static boolean upgrade;
    /** The repository being installed into (null for --user): files are recorded relative to it. */
    static Path base;
    /** What .appmod/kit.json said before this run (--upgrade), and what it will say after (path to SHA-256). */
    static Map<String, String> recorded = new java.util.TreeMap<>();
    static final Map<String, String> placed = new java.util.TreeMap<>();
    static final List<String> report = new ArrayList<>();

    /** The JDK the kit is built and tested on; the validators need Java 25's multi-file source launch. */
    static final int REQUIRED_JDK = 25;

    public static void main(String[] args) throws IOException {
        if (Runtime.version().feature() < REQUIRED_JDK) {
            System.err.println("error: the kit needs JDK %d or later, and this is JDK %d. Run appmod.cmd, which finds or downloads a JDK %d."
                    .formatted(REQUIRED_JDK, Runtime.version().feature(), REQUIRED_JDK));
            System.exit(2);
        }
        try {
            run(args);
        } catch (IllegalStateException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    static void run(String[] args) throws IOException {
        Path kit = locateKit();
        Path target = null;
        boolean user = false;
        boolean withMcp = false;
        String tool = "copilot";
        boolean toolGiven = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> target = Path.of(args[++i]).toAbsolutePath().normalize();
                case "--user" -> user = true;
                case "--force" -> force = true;
                case "--upgrade" -> upgrade = true;
                case "--dry-run" -> dryRun = true;
                case "--with-mcp" -> withMcp = true;
                case "--tool" -> {
                    tool = i + 1 < args.length ? args[++i] : "";
                    toolGiven = true;
                }
                default -> usage("unknown option " + args[i]);
            }
        }
        if (!List.of("copilot", "claude", "both").contains(tool)) {
            usage("--tool must be copilot, claude or both, but is: " + tool);
        }
        if (target == null && !user) {
            usage("pass --target <repo> or --user");
        }
        if (upgrade) {
            if (target == null || user) {
                usage("--upgrade works on a repository: pass --target <repo> (and not --user)");
            }
            String before = readRecord(target);
            if (toolGiven && !before.equals(tool)) {
                usage("--tool " + tool + " does not match the recorded tool (" + before + "): leave --tool out to upgrade as recorded");
            }
            tool = before;
        }
        boolean copilot = !tool.equals("claude");
        boolean claude = !tool.equals("copilot");
        Path gh = kit.resolve(".github");
        // Everything for Claude Code is rendered first, so a source the installer cannot render stops the run before any file is written.
        List<Rendered> agents = claude ? renderAgents(gh.resolve("agents")) : List.of();
        if (target != null) {
            if (!Files.isDirectory(target)) {
                usage("target does not exist: " + target);
            }
            base = target;
            Path ghTarget = target.resolve(".github");
            if (copilot) {
                for (String sub : List.of("agents", "skills", "instructions", "prompts", "modernize/rulebook", "appmod/tools")) {
                    copyTree(gh.resolve(sub), ghTarget.resolve(sub));
                }
                mergeBlock(Files.readString(gh.resolve("copilot-instructions.md")), ghTarget.resolve("copilot-instructions.md"));
            } else {
                // The agents run the Java tools and read the rulebook by these paths, whichever tool starts them.
                for (String sub : List.of("modernize/rulebook", "appmod/tools")) {
                    copyTree(gh.resolve(sub), ghTarget.resolve(sub));
                }
            }
            if (claude) {
                Path dot = target.resolve(".claude");
                placeAgents(agents, dot.resolve("agents"));
                copySkills(gh.resolve("skills"), dot.resolve("skills"), true);
                renderRules(gh.resolve("instructions"), dot.resolve("rules"));
                mergeBlock(claudeMemoryBlock(agents), target.resolve("CLAUDE.md"));
            }
            if (withMcp) {
                if (copilot) {
                    copyFile(kit.resolve(".vscode/mcp.json"), target.resolve(".vscode/mcp.json"));
                }
                if (claude) {
                    place(target.resolve(".mcp.json"), claudeMcpConfig(kit).getBytes(StandardCharsets.UTF_8));
                }
            }
            addGitignore(target);
            writeRecord(target, tool);
        }
        if (user) {
            if (copilot) {
                Path home = Path.of(System.getProperty("user.home"), ".copilot");
                copyTree(gh.resolve("agents"), home.resolve("agents"));
                copyTree(gh.resolve("skills"), home.resolve("skills"));
                copyTree(gh.resolve("appmod/tools"), home.resolve("appmod/tools"));
                report.add("note: instructions, prompts and the rulebook are repository-level – install them with --target.");
                report.add("note: for the app-modernization MCP server in Copilot CLI merge copilot-cli/mcp-config.json into "
                        + home.resolve("mcp-config.json"));
            }
            if (claude) {
                Path home = Path.of(System.getProperty("user.home"), ".claude");
                placeAgents(agents, home.resolve("agents"));
                copySkills(gh.resolve("skills"), home.resolve("skills"), true);
                copyTree(gh.resolve("appmod/tools"), home.resolve("appmod/tools"));
                report.add("note: rules and CLAUDE.md are repository-level – install them with --target.");
                report.add("note: for the app-modernization MCP server in Claude Code use .mcp.json (--target --with-mcp) or `claude mcp add`.");
            }
        }
        report.forEach(System.out::println);
        System.out.println(dryRun ? "(dry run - nothing written)"
                : "Kit files done. Tools and the app modernization plugin are installed by: appmod init <working-directory>");
    }

    static Path locateKit() {
        Path p = Path.of("").toAbsolutePath();
        for (Path c = p; c != null; c = c.getParent()) {
            if (Files.isDirectory(c.resolve(".github/skills/code-knowledge-graph"))) {
                return c;
            }
            if (Files.isDirectory(c.resolve("kit/.github"))) {
                return c.resolve("kit");
            }
        }
        usage("run from the kit folder (kit) or the solution root");
        return null;
    }

    // ---- Copilot (and tool-neutral) files: copied as they are ----

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                copyFile(file, to.resolve(from.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void copyFile(Path from, Path to) throws IOException {
        place(to, Files.readAllBytes(from));
    }

    /**
     * Writes a file by the installer's rules: identical is "unchanged", a different existing file is kept unless --force, or unless
     * --upgrade finds it exactly as the installer recorded it (nobody edited it).
     */
    static void place(Path to, byte[] content) throws IOException {
        String key = key(to);
        String hash = sha256(content);
        boolean exists = Files.exists(to);
        byte[] current = exists ? Files.readAllBytes(to) : null;
        if (exists && java.util.Arrays.equals(current, content)) {
            report.add("unchanged      " + to);
            remember(key, hash);
            return;
        }
        String known = key == null ? null : recorded.get(key);
        boolean untouched = known != null && exists && known.equals(sha256(current));
        if (exists && !force && !(upgrade && untouched)) {
            report.add((known != null ? "skip (edited)  " : "skip (exists)  ") + to);
            if (known != null) {
                remember(key, known);
            }
            return;
        }
        report.add((exists ? (upgrade && untouched ? "upgrade        " : "overwrite      ") : "create         ") + to);
        if (!dryRun) {
            Files.createDirectories(to.getParent());
            Files.write(to, content);
        }
        remember(key, hash);
    }

    static String key(Path to) {
        return base != null && to.startsWith(base) ? base.relativize(to).toString().replace('\\', '/') : null;
    }

    static void remember(String key, String hash) {
        if (key != null) {
            placed.put(key, hash);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the tool and the recorded hashes of an earlier install; stops with a message when there is none. */
    static String readRecord(Path target) throws IOException {
        Path file = target.resolve(".appmod/kit.json");
        if (!Files.isRegularFile(file)) {
            usage("no " + file + ": install first (java install/KitInstaller.java --target <repo> --tool ...); --upgrade needs the record of that install");
        }
        String tool = null;
        Matcher toolLine = Pattern.compile("\"tool\":\\s*\"(copilot|claude|both)\"").matcher(Files.readString(file));
        if (toolLine.find()) {
            tool = toolLine.group(1);
        }
        if (tool == null) {
            usage(file + " does not name a tool");
        }
        Pattern entry = Pattern.compile("^\\s*\"([^\"]+)\":\\s*\"([0-9a-f]{64})\",?\\s*$");
        for (String line : Files.readAllLines(file)) {
            Matcher m = entry.matcher(line);
            if (m.matches()) {
                recorded.put(m.group(1), m.group(2));
            }
        }
        return tool;
    }

    /** Writes the record of this install: the tool, an id of the kit content, and the hash of every placed file. */
    static void writeRecord(Path target, String tool) throws IOException {
        String id = sha256(placed.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"))
                .getBytes(StandardCharsets.UTF_8)).substring(0, 12);
        String json = "{\n  \"tool\": \"" + tool + "\",\n  \"kit\": \"" + id + "\",\n  \"files\": {\n"
                + placed.entrySet().stream().map(e -> "    \"" + e.getKey() + "\": \"" + e.getValue() + "\"").collect(Collectors.joining(",\n"))
                + "\n  }\n}\n";
        Path file = target.resolve(".appmod/kit.json");
        if (Files.exists(file) && Files.readString(file).equals(json)) {
            report.add("unchanged      " + file);
            return;
        }
        report.add((Files.exists(file) ? "overwrite      " : "create         ") + file + "  (record of this install)");
        if (!dryRun) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json);
        }
    }

    /** Appends a marked block to a file (creating it), once: a file that has the block is left alone. */
    static void mergeBlock(String text, Path to) throws IOException {
        String block = "\n" + MARK_START + "\n" + (text.endsWith("\n") ? text : text + "\n") + MARK_END + "\n";
        if (!Files.exists(to)) {
            report.add("create         " + to);
            if (!dryRun) {
                Files.createDirectories(to.getParent());
                Files.writeString(to, block.strip() + "\n");
            }
            return;
        }
        String existing = Files.readString(to);
        if (existing.contains(MARK_START)) {
            report.add("skip (merged)  " + to);
            return;
        }
        report.add("append block   " + to);
        if (!dryRun) {
            Files.writeString(to, existing + block);
        }
    }

    static void addGitignore(Path target) throws IOException {
        Path gi = target.resolve(".gitignore");
        String existing = Files.exists(gi) ? Files.readString(gi) : "";
        if (!existing.contains(".appmod/")) {
            report.add("append         " + gi + "  (.appmod/)");
            if (!dryRun) {
                Files.writeString(gi, existing + (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n")
                        + "# AppMod kit working files (reports, knowledge graph, advisory)\n.appmod/\n");
            }
        }
    }

    // ---- Claude Code: rendered from the same sources ----

    /** A Claude Code file ready to be placed. */
    record Rendered(String name, String content) {
    }

    /** Renders every kit agent that works in Claude Code; refuses (before anything is written) one it cannot render. */
    static List<Rendered> renderAgents(Path agentsFolder) throws IOException {
        List<Rendered> out = new ArrayList<>();
        if (!Files.isDirectory(agentsFolder)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(agentsFolder)) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".agent.md")).sorted().toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString().replaceAll("\\.agent\\.md$", "");
            String reason = COPILOT_ONLY_AGENTS.get(name);
            if (reason != null) {
                report.add("skip (Copilot only)  agent " + name + ": " + reason);
                continue;
            }
            out.add(new Rendered(name, renderAgent(name, Files.readString(file))));
        }
        return out;
    }

    static String renderAgent(String name, String source) {
        String text = source.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw fail("agent " + name + " has no frontmatter");
        }
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) {
            throw fail("agent " + name + " has no end of the frontmatter");
        }
        String body = text.substring(end + "\n---\n".length());
        List<String> front = new ArrayList<>();
        boolean hasName = false;
        boolean hasDescription = false;
        for (String line : text.substring(4, end).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher key = AGENT_KEY.matcher(line);
            if (!key.matches()) {
                throw fail("agent " + name + ": the frontmatter line is not 'key: value': " + line);
            }
            switch (key.group(1)) {
                case "name" -> {
                    hasName = true;
                    front.add(line);
                }
                case "description" -> {
                    hasDescription = true;
                    front.add(line);
                }
                case "tools" -> front.add("tools: " + String.join(", ", mapTools(name, key.group(2))));
                case "user-invocable" -> { /* Copilot only */ }
                default -> throw fail("agent " + name + ": the frontmatter key '" + key.group(1) + "' is not known to the installer");
            }
        }
        if (!hasName || !hasDescription) {
            throw fail("agent " + name + " needs a name and a description");
        }
        return "---\n" + String.join("\n", front) + "\n---\n" + rewritePaths(body);
    }

    static List<String> mapTools(String agent, String value) {
        String trimmed = value.strip();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw fail("agent " + agent + ": tools is not a list written as [\"a\", \"b\"]: " + value);
        }
        List<String> mapped = new ArrayList<>();
        String inner = trimmed.substring(1, trimmed.length() - 1).strip();
        if (inner.isEmpty()) {
            return mapped;
        }
        for (String item : inner.split(",")) {
            String tool = item.strip().replaceAll("^\"|\"$", "");
            // An MCP tool is written server/tool for Copilot; Claude Code names it mcp__server__tool (and mcp__server for every tool of the server).
            Matcher mcp = MCP_TOOL.matcher(tool);
            if (mcp.matches()) {
                String name = mcp.group(2).equals("*") ? "mcp__" + mcp.group(1) : "mcp__" + mcp.group(1) + "__" + mcp.group(2);
                if (!mapped.contains(name)) {
                    mapped.add(name);
                }
                continue;
            }
            List<String> claude = CLAUDE_TOOLS.get(tool);
            if (claude == null) {
                throw fail("agent " + agent + " lists the tool '" + tool + "', which the installer cannot map to Claude Code ("
                        + CLAUDE_TOOLS.keySet().stream().sorted().collect(Collectors.joining(", ")) + " are known)");
            }
            for (String c : claude) {
                if (!mapped.contains(c)) {
                    mapped.add(c);
                }
            }
        }
        return mapped;
    }

    static String rewritePaths(String text) {
        String out = text;
        for (Map.Entry<String, String> e : CLAUDE_PATHS.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    static void placeAgents(List<Rendered> agents, Path folder) throws IOException {
        for (Rendered agent : agents) {
            place(folder.resolve(agent.name() + ".md"), agent.content().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Skills are the same SKILL.md in both tools; only text files get their kit paths rewritten. */
    static void copySkills(Path from, Path to, boolean rewrite) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = to.resolve(from.relativize(file).toString());
                byte[] bytes = Files.readAllBytes(file);
                if (rewrite && file.getFileName().toString().endsWith(".md")) {
                    bytes = rewritePaths(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                }
                place(dest, bytes);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** {@code .github/instructions/<n>.instructions.md} becomes {@code .claude/rules/<n>.md}; applyTo "**" means no path restriction. */
    static void renderRules(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(from)) {
            files = list.filter(f -> f.getFileName().toString().endsWith(".instructions.md")).sorted().toList();
        }
        for (Path file : files) {
            String name = file.getFileName().toString().replaceAll("\\.instructions\\.md$", "");
            place(to.resolve(name + ".md"), renderRule(name, Files.readString(file)).getBytes(StandardCharsets.UTF_8));
        }
    }

    static String renderRule(String name, String source) {
        String text = source.replace("\r\n", "\n");
        if (!text.startsWith("---\n") || text.indexOf("\n---\n", 3) < 0) {
            throw fail("instruction file " + name + " has no frontmatter");
        }
        int end = text.indexOf("\n---\n", 3);
        String body = text.substring(end + "\n---\n".length());
        String applyTo = null;
        for (String line : text.substring(4, end).split("\n")) {
            Matcher key = AGENT_KEY.matcher(line);
            if (key.matches() && key.group(1).equals("applyTo")) {
                applyTo = key.group(2).strip().replaceAll("^\"|\"$", "");
            }
        }
        if (applyTo == null) {
            throw fail("instruction file " + name + " has no applyTo");
        }
        String front = "";
        if (!applyTo.equals("**")) {
            front = "---\npaths:\n" + Stream.of(applyTo.split(",")).map(g -> "  - \"" + g.strip() + "\"\n").collect(Collectors.joining()) + "---\n";
        }
        return front + rewritePaths(body);
    }

    /** What CLAUDE.md says for this kit: a fixed text, because the Copilot file starts with the agent that Claude Code does not get. */
    static String claudeMemoryBlock(List<Rendered> agents) {
        String names = agents.stream().map(a -> "`" + a.name() + "`").collect(Collectors.joining(", "));
        return """
                # AppMod kit (Claude Code)

                This repository has the **AppMod kit**: agents for security audit and fixing, dependency advice and verification.

                - Agents in `.claude/agents/`: %s. Start the security work with `security-audit`, then `security-fixer` on the CSV it writes.
                - The front door `appmod-harness` is not available here: it delegates to the GitHub Copilot app modernization plugin.
                - Ground rules: `.claude/rules/appmod-harness.md`. Planning policies: `.github/modernize/rulebook/`.
                - Skills in `.claude/skills/`. Tools: `.github/appmod/tools/*.java` (run with `java <File>.java`, JDK 25+).
                """.formatted(names.isEmpty() ? "none" : names);
    }

    /** The app-modernization MCP server for Claude Code, at the version the Copilot configuration pins. */
    static String claudeMcpConfig(Path kit) throws IOException {
        Matcher m = MCP_VERSION.matcher(Files.readString(kit.resolve("copilot-cli/mcp-config.json")));
        if (!m.find()) {
            throw fail("copilot-cli/mcp-config.json does not name the app-modernization MCP server version");
        }
        return """
                {
                  "mcpServers": {
                    "appmod": {
                      "command": "npx",
                      "args": ["-y", "@microsoft/github-copilot-app-modernization-mcp-server@%s"]
                    }
                  }
                }
                """.formatted(m.group(1));
    }

    static IllegalStateException fail(String message) {
        return new IllegalStateException(message);
    }

    static void usage(String msg) {
        System.err.println(msg + "\nusage: java install/KitInstaller.java (--target <repo> [--upgrade] | --user) [--tool copilot|claude|both] [--force] [--dry-run] [--with-mcp]");
        System.exit(2);
    }
}
