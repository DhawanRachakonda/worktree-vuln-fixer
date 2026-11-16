import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The machine configuration of {@code appmod setup} and {@code appmod start}: one Java properties file, {@code machine.properties}, in
 * the appmod folder ({@code APPMOD_HOME}, default {@code %LOCALAPPDATA%\appmod}). JDK only, no dependencies, no {@code package} line.
 * It holds paths and two answers and no secret. It is written completely or not at all (a temporary file moved into place).
 */
public final class MachineConfig {

    /** What the file holds. {@code allowedRoots} are the folders of the person's repositories, as {@code SUBSCRIBER_ALLOWED_ROOTS} takes them. */
    record Values(String javaHome, String natsServer, int natsPort, String engineJar, List<String> allowedRoots, String agentTool,
            String dataDir) {
    }

    /** Why the file cannot be used; the text is shown to the person. */
    static final class ConfigProblem extends Exception {
        private static final long serialVersionUID = 1L;

        ConfigProblem(String message) {
            super(message);
        }
    }

    static final String FILE = "machine.properties";
    static final int DEFAULT_PORT = 4222;

    private MachineConfig() {
    }

    /** The appmod folder: {@code APPMOD_HOME}, else {@code %LOCALAPPDATA%\appmod}, else {@code ~/.appmod}. */
    static Path home() {
        String home = System.getenv("APPMOD_HOME");
        if (home != null && !home.isBlank()) {
            return Path.of(home);
        }
        String local = System.getenv("LOCALAPPDATA");
        return local != null && !local.isBlank() ? Path.of(local, "appmod") : Path.of(System.getProperty("user.home"), ".appmod");
    }

    static Path file(Path home) {
        return home.resolve(FILE);
    }

    /** Why a folder cannot be one of the repository folders, or null: absolute, existing, no control characters, no comma. */
    static String folderProblem(String folder) {
        if (folder == null || folder.isBlank()) {
            return "the folder is empty";
        }
        if (folder.chars().anyMatch(c -> c < 32) || folder.indexOf(',') >= 0) {
            return "a folder may not contain a comma or a control character: " + printable(folder);
        }
        Path path;
        try {
            path = Path.of(folder.strip());
        } catch (RuntimeException _) {
            return "that is not a valid path: " + printable(folder);
        }
        if (!path.isAbsolute()) {
            return "the folder must be an absolute path, but is: " + printable(folder);
        }
        if (!Files.exists(path)) {
            return "the folder does not exist: " + printable(folder);
        }
        if (!Files.isDirectory(path)) {
            return "that is a file, not a folder: " + printable(folder);
        }
        return null;
    }

    /** Why a tool name is not accepted, or null. */
    static String toolProblem(String tool) {
        return "copilot".equals(tool) || "claude".equals(tool) ? null : "the tool must be copilot or claude, but is: " + printable(tool);
    }

    /** Reads and validates the file (the values, not whether the programs still exist: see {@link #missingFiles}). */
    static Values read(Path file) throws ConfigProblem {
        if (!Files.exists(file)) {
            throw new ConfigProblem("there is no machine configuration (%s)".formatted(file));
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            throw new ConfigProblem("%s cannot be read".formatted(file));
        }
        String java = required(p, "javaHome", file);
        String nats = required(p, "natsServer", file);
        String jar = required(p, "engineJar", file);
        String roots = required(p, "allowedRoots", file);
        String tool = required(p, "agentTool", file);
        String problem = toolProblem(tool);
        if (problem != null) {
            throw new ConfigProblem("%s: agentTool: %s".formatted(file, problem));
        }
        int port;
        try {
            port = Integer.parseInt(p.getProperty("natsPort", String.valueOf(DEFAULT_PORT)).strip());
        } catch (NumberFormatException _) {
            throw new ConfigProblem("%s: natsPort is not a number".formatted(file));
        }
        if (port < 1 || port > 65535) {
            throw new ConfigProblem("%s: natsPort must be between 1 and 65535".formatted(file));
        }
        List<String> folders = new ArrayList<>();
        for (String root : roots.split(",")) {
            if (!root.isBlank()) {
                String why = folderProblem(root);
                if (why != null && !why.startsWith("the folder does not exist")) {
                    throw new ConfigProblem("%s: allowedRoots: %s".formatted(file, why));
                }
                folders.add(root.strip());
            }
        }
        if (folders.isEmpty()) {
            throw new ConfigProblem("%s: allowedRoots names no folder".formatted(file));
        }
        String data = p.getProperty("dataDir", "").isBlank() ? file.getParent().resolve("engine-data").toString() : p.getProperty("dataDir").strip();
        return new Values(java, nats, port, jar, List.copyOf(folders), tool, data);
    }

    private static String required(Properties p, String key, Path file) throws ConfigProblem {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new ConfigProblem("%s: %s is missing".formatted(file, key));
        }
        if (value.chars().anyMatch(c -> c < 32)) {
            throw new ConfigProblem("%s: %s holds a control character".formatted(file, key));
        }
        return value.strip();
    }

    /** The programs and folders named in the values that no longer exist. */
    static List<String> missingFiles(Values v) {
        List<String> missing = new ArrayList<>();
        if (!Files.isRegularFile(Path.of(v.javaHome(), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"))) {
            missing.add("the JDK at " + v.javaHome());
        }
        if (!Files.isRegularFile(Path.of(v.natsServer()))) {
            missing.add("the nats-server program " + v.natsServer());
        }
        if (!Files.isRegularFile(Path.of(v.engineJar()))) {
            missing.add("the engine program " + v.engineJar());
        }
        return missing;
    }

    /** Writes the file completely or not at all: a temporary file in the same folder, then moved over the old one. */
    static void write(Path file, Values v) throws IOException {
        Files.createDirectories(file.getParent());
        Properties p = new Properties();
        p.setProperty("javaHome", v.javaHome());
        p.setProperty("natsServer", v.natsServer());
        p.setProperty("natsPort", String.valueOf(v.natsPort()));
        p.setProperty("engineJar", v.engineJar());
        p.setProperty("allowedRoots", String.join(",", v.allowedRoots()));
        p.setProperty("agentTool", v.agentTool());
        p.setProperty("dataDir", v.dataDir());
        Path temp = Files.createTempFile(file.getParent(), "machine-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                p.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8),
                        "appmod machine configuration, written by appmod setup. It holds paths and two answers, no secret.");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String printable(String text) {
        if (text == null) {
            return "(none)";
        }
        String cleaned = text.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) + "..." : cleaned;
    }
}
