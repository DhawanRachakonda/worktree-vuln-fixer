import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@code appmod setup}: prepares this machine, once, so that {@code appmod start} can run the engine. JDK 25, no dependencies, no
 * {@code package} line. {@code appmod.cmd} runs it with the JDK it found and passes it with {@code --java-home}.
 *
 * <pre>
 *   java install/AppmodSetup.java --java-home &lt;jdk&gt; --kit-dir &lt;kit&gt; [--roots a,b] [--tool copilot|claude] [--yes] [--check]
 *                                 [--nats-port 4222] [--engine-jar &lt;path&gt;]
 * </pre>
 *
 * Environment: {@code APPMOD_HOME} (where the configuration and the tools go), {@code APPMOD_NATS_VERSION} with {@code APPMOD_NATS_SHA256}
 * (a different pinned version; a version without its hash is refused) and {@code APPMOD_NATS_BASE_URL} (a test aid: where the release is
 * served from). With {@code --path-additions-file} the folders that {@code appmod.cmd} offers to add to the user PATH are written to that file
 * ({@code ;dir;dir}); setup itself never changes the PATH.
 * No password or token is asked for or stored.
 */
public final class AppmodSetup {

    /** The pinned nats-server release and the SHA-256 of its Windows archive (the same value its release page publishes in SHA256SUMS). */
    static final String PINNED_VERSION = "v2.10.29";
    static final String PINNED_SHA256 = "98657bf4d5a9ce44168c019ba6894cda8e22e6adc8798edc05c168db7262de29";
    static final String DEFAULT_BASE_URL = "https://github.com/nats-io/nats-server/releases/download";

    private static boolean yes;
    private static BufferedReader answers;

    private AppmodSetup() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Stop e) {
            System.err.println("error: " + e.getMessage());
            System.exit(e.exit);
        } catch (IOException e) {
            System.err.println("error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** A reason to stop the setup; nothing is saved. */
    static final class Stop extends Exception {
        private static final long serialVersionUID = 1L;
        final int exit;

        Stop(String message, int exit) {
            super(message);
            this.exit = exit;
        }
    }

    static int run(String[] args) throws Stop, IOException {
        String javaHome = null;
        String kitDir = null;
        String rootsFlag = null;
        String toolFlag = null;
        String portFlag = null;
        String engineJarFlag = null;
        String pathFile = null;
        boolean check = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--java-home" -> javaHome = value(args, ++i);
                case "--kit-dir" -> kitDir = value(args, ++i);
                case "--roots" -> rootsFlag = value(args, ++i);
                case "--tool" -> toolFlag = value(args, ++i);
                case "--nats-port" -> portFlag = value(args, ++i);
                case "--engine-jar" -> engineJarFlag = value(args, ++i);
                case "--path-additions-file" -> pathFile = value(args, ++i);
                case "--yes", "-y" -> yes = true;
                case "--check" -> check = true;
                default -> throw new Stop("unknown option %s (known: --java-home, --kit-dir, --roots, --tool, --nats-port, --engine-jar, --path-additions-file, --yes, --check)".formatted(args[i]), 2);
            }
        }
        Path home = MachineConfig.home();
        Path file = MachineConfig.file(home);
        MachineConfig.Values earlier = null;
        try {
            earlier = MachineConfig.read(file);
        } catch (MachineConfig.ConfigProblem _) {
            // no earlier answers (or an unusable file, which is replaced only when this run succeeds)
        }
        answers = answerSource();

        // 1. the JDK
        String jdk = javaHome != null ? javaHome : earlier != null ? earlier.javaHome() : null;
        if (jdk == null || !Files.isRegularFile(Path.of(jdk, "bin", exeName("java")))) {
            throw new Stop("no JDK 25 was given (--java-home) and none is recorded; run appmod setup through appmod.cmd, which finds or installs one", 1);
        }
        System.out.println("JDK:         " + jdk);

        // 2. nats-server
        List<String> pathAdditions = new ArrayList<>();
        String nats = findNats(home, check, pathAdditions);

        // 3. the engine program
        String jar = engineJar(engineJarFlag, kitDir, jdk, check, earlier);

        if (check) {
            boolean ok = nats != null && jar != null && earlier != null;
            System.out.println("nats-server: " + (nats == null ? "MISSING (appmod setup downloads the pinned one)" : nats));
            System.out.println("engine:      " + (jar == null ? "MISSING (appmod setup builds it)" : jar));
            System.out.println("settings:    " + (earlier == null ? "MISSING (appmod setup asks for the folders and the tool)" : file));
            return ok ? 0 : 1;
        }

        // 4. the two answers
        List<String> roots = rootsFlag != null ? checkedRoots(rootsFlag) : askRoots(earlier);
        String tool = toolFlag != null ? checkedTool(toolFlag) : askTool(earlier);
        int port = portFlag != null ? Integer.parseInt(portFlag.strip()) : earlier != null ? earlier.natsPort() : MachineConfig.DEFAULT_PORT;
        String data = earlier != null ? earlier.dataDir() : home.resolve("engine-data").toString();

        // 5. save, complete or not at all
        MachineConfig.write(file, new MachineConfig.Values(jdk, nats, port, jar, roots, tool, data));
        System.out.println("Saved " + file);
        System.out.println("  folders: " + String.join(", ", roots));
        System.out.println("  agent:   " + tool);
        System.out.println("  NATS:    " + nats + " (port " + port + ", loopback only)");
        System.out.println("  engine:  " + jar);
        System.out.println("Next: appmod init <repository> for each repository, then appmod start.");
        if (!pathAdditions.isEmpty()) {
            System.out.println("Folder for your PATH (appmod.cmd offers to add it): " + String.join("; ", pathAdditions));
            if (pathFile != null) {
                Files.writeString(Path.of(pathFile), ";" + String.join(";", pathAdditions));
            }
        }
        return 0;
    }

    // ---- nats-server ----------------------------------------------------------------------------------------------------------

    static String findNats(Path home, boolean check, List<String> pathAdditions) throws Stop, IOException {
        String version = System.getenv("APPMOD_NATS_VERSION") == null || System.getenv("APPMOD_NATS_VERSION").isBlank() ? PINNED_VERSION : System.getenv("APPMOD_NATS_VERSION").strip();
        String sha = System.getenv("APPMOD_NATS_SHA256") == null || System.getenv("APPMOD_NATS_SHA256").isBlank()
                ? (version.equals(PINNED_VERSION) ? PINNED_SHA256 : null) : System.getenv("APPMOD_NATS_SHA256").strip();
        Path inTools = home.resolve("tools").resolve("nats-server-" + version).resolve(exeName("nats-server"));
        if (Files.isRegularFile(inTools)) {
            System.out.println("nats-server: " + inTools + " (already downloaded)");
            return inTools.toString();
        }
        String onPath = onPath("nats-server");
        if (onPath != null) {
            System.out.println("nats-server: " + onPath + " (found on the PATH, version: " + versionOf(onPath) + ")");
            return onPath;
        }
        if (check) {
            return null;
        }
        if (sha == null) {
            throw new Stop("nats-server %s has no pinned SHA-256 (set APPMOD_NATS_SHA256 as well): an unverified download is never made".formatted(version), 1);
        }
        String base = System.getenv("APPMOD_NATS_BASE_URL") == null || System.getenv("APPMOD_NATS_BASE_URL").isBlank() ? DEFAULT_BASE_URL : System.getenv("APPMOD_NATS_BASE_URL").strip();
        URI uri = URI.create("%s/%s/nats-server-%s-windows-amd64.zip".formatted(base.replaceAll("/+$", ""), version, version));
        if (!confirm("nats-server was not found. Download %s from %s into %s (about 6 MB, the SHA-256 is checked)?".formatted(version, uri, inTools.getParent()))) {
            throw new Stop("nats-server is needed. Install it yourself and put it on the PATH, then run appmod setup again (https://github.com/nats-io/nats-server/releases)", 1);
        }
        Path zip = home.resolve("tools").resolve("nats-server-" + version + ".zip");
        System.out.println("Downloading " + uri);
        try {
            Downloads.download(uri, zip);
            Downloads.verify(zip, sha);
            Path program = Downloads.extractOnly(zip, inTools.getParent(), exeName("nats-server"));
            System.out.println("nats-server: " + program + " (downloaded, SHA-256 verified)");
            pathAdditions.add(inTools.getParent().toString());
            return program.toString();
        } catch (Downloads.DownloadProblem e) {
            throw new Stop(e.getMessage() + ". You can install nats-server yourself and put it on the PATH; appmod setup finds it.", 1);
        }
    }

    static String versionOf(String program) {
        try {
            Process p = new ProcessBuilder(program, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            p.waitFor(10, TimeUnit.SECONDS);
            return out.isEmpty() ? "unknown" : out.lines().findFirst().orElse("unknown");
        } catch (IOException | InterruptedException _) {
            return "unknown";
        }
    }

    // ---- the engine -------------------------------------------------------------------------------------------------------------

    static String engineJar(String flag, String kitDir, String jdk, boolean check, MachineConfig.Values earlier) throws Stop, IOException {
        if (flag != null) {
            Path jar = Path.of(flag).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) {
                throw new Stop("--engine-jar names a file that does not exist: " + jar, 1);
            }
            System.out.println("engine:      " + jar + " (given)");
            return jar.toString();
        }
        Path engine = kitDir == null ? null : Path.of(kitDir).toAbsolutePath().normalize().getParent().resolve("engine");
        if (engine == null || !Files.isDirectory(engine)) {
            if (earlier != null && Files.isRegularFile(Path.of(earlier.engineJar()))) {
                System.out.println("engine:      " + earlier.engineJar() + " (recorded earlier)");
                return earlier.engineJar();
            }
            if (check) {
                return null;
            }
            throw new Stop("there is no engine folder next to the kit, so the engine cannot be built here; pass --engine-jar <path> for a program built elsewhere", 1);
        }
        if (check) {
            return newestJar(engine);
        }
        System.out.println("Building the engine (gradlew bootJar, JAVA_HOME=" + jdk + ") ...");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = new ArrayList<>(List.of(engine.resolve(windows ? "gradlew.bat" : "gradlew").toString(), "bootJar", "--console=plain", "-q"));
        ProcessBuilder builder = new ProcessBuilder(command).directory(engine.toFile()).redirectErrorStream(true);
        builder.environment().put("JAVA_HOME", jdk);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(30, TimeUnit.MINUTES) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new Stop("the engine build failed:\n" + output.lines().limit(60).reduce((a, b) -> a + "\n" + b).orElse(""), 1);
            }
        } catch (InterruptedException _) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new Stop("interrupted while the engine was built", 1);
        }
        String jar = newestJar(engine);
        if (jar == null) {
            throw new Stop("the build finished but no engine program was found in " + engine.resolve("build/libs"), 1);
        }
        System.out.println("engine:      " + jar + " (built)");
        return jar;
    }

    static String newestJar(Path engine) throws IOException {
        Path libs = engine.resolve("build").resolve("libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> jars = Files.list(libs)) {
            return jars.filter(p -> p.getFileName().toString().startsWith("subscriber-engine-") && p.toString().endsWith(".jar")
                    && !p.toString().endsWith("-plain.jar")).max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .map(Path::toString).orElse(null);
        }
    }

    // ---- the two questions -------------------------------------------------------------------------------------------------------

    static List<String> checkedRoots(String value) throws Stop {
        List<String> roots = new ArrayList<>();
        for (String root : value.split(",")) {
            if (!root.isBlank()) {
                String problem = MachineConfig.folderProblem(root);
                if (problem != null) {
                    throw new Stop("--roots: " + problem, 1);
                }
                roots.add(root.strip());
            }
        }
        if (roots.isEmpty()) {
            throw new Stop("--roots names no folder", 1);
        }
        return roots;
    }

    static String checkedTool(String value) throws Stop {
        String problem = MachineConfig.toolProblem(value.strip());
        if (problem != null) {
            throw new Stop("--tool: " + problem, 1);
        }
        return value.strip();
    }

    static List<String> askRoots(MachineConfig.Values earlier) throws Stop, IOException {
        String suggestion = earlier == null ? null : String.join(",", earlier.allowedRoots());
        if (yes && suggestion != null) {
            return earlier.allowedRoots();
        }
        if (yes) {
            throw new Stop("--yes needs --roots on a first run: the folders of your repositories cannot be guessed", 1);
        }
        while (true) {
            String line = ask("Which folders hold your repositories (absolute paths, comma separated)%s? ".formatted(suggestion == null ? "" : " [" + suggestion + "]"));
            if (line == null) {
                throw new Stop("no answer for the folders (there is no terminal): give --roots", 1);
            }
            String text = line.isBlank() && suggestion != null ? suggestion : line;
            try {
                return checkedRoots(text);
            } catch (Stop problem) {
                System.out.println("  " + problem.getMessage().replaceFirst("^--roots: ", "") + " - try again");
            }
        }
    }

    static String askTool(MachineConfig.Values earlier) throws Stop, IOException {
        String suggestion = earlier != null ? earlier.agentTool() : "copilot";
        if (yes) {
            return suggestion;
        }
        while (true) {
            String line = ask("Which coding agent do you use, copilot or claude [%s]? ".formatted(suggestion));
            if (line == null) {
                throw new Stop("no answer for the agent (there is no terminal): give --tool", 1);
            }
            String text = line.isBlank() ? suggestion : line.strip();
            if (MachineConfig.toolProblem(text) == null) {
                return text;
            }
            System.out.println("  " + MachineConfig.toolProblem(text) + " - try again");
        }
    }

    static boolean confirm(String question) throws IOException {
        if (yes) {
            return true;
        }
        String line = ask(question + " [Y/n] ");
        return line == null ? false : line.isBlank() || line.strip().toLowerCase(Locale.ROOT).startsWith("y");
    }

    static String ask(String prompt) throws IOException {
        if (answers == null) {
            return null;
        }
        System.out.print(prompt);
        System.out.flush();
        String line = answers.readLine();
        if (System.getProperty("appmod.setup.answers") != null && line != null) {
            System.out.println(line);
        }
        return line;
    }

    /** The terminal, or the file named by {@code -Dappmod.setup.answers} (one answer per line; used by the tests), or nothing. */
    static BufferedReader answerSource() throws IOException {
        String file = System.getProperty("appmod.setup.answers");
        if (file != null) {
            return Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8);
        }
        var console = System.console();
        return console != null && console.isTerminal() ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
    }

    // ---- small helpers --------------------------------------------------------------------------------------------------------

    static String value(String[] args, int index) throws Stop {
        if (index >= args.length) {
            throw new Stop("%s needs a value".formatted(args[index - 1]), 2);
        }
        return args[index];
    }

    static String exeName(String name) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
    }

    static String onPath(String program) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (!directory.isBlank()) {
                try {
                    Path candidate = Path.of(directory.strip().replace("\"", "")).resolve(exeName(program));
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                } catch (RuntimeException _) {
                    // a PATH entry that is not a path cannot hold the program
                }
            }
        }
        return null;
    }
}
