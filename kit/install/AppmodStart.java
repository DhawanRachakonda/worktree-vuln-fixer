import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code appmod start [--nats|--engine]}: runs what the engine needs, from the machine configuration that {@code appmod setup} saved.
 * JDK 25, no dependencies, no {@code package} line. With no option both run: NATS in the background (bound to 127.0.0.1) and the engine in
 * the foreground of this terminal, so the engine's console works. An answering NATS server is reused; a NATS server this command began is
 * stopped when the engine ends. The engine gets only the folders, the tool, the data folder and the NATS address.
 */
public final class AppmodStart {

    private enum Port { NATS, OTHER, CLOSED }

    private static Process startedNats;
    private static Path pidFile;

    private AppmodStart() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args);
        } catch (Problem e) {
            System.err.println("error: " + e.getMessage());
            exit = e.exit;
        }
        System.exit(exit);
    }

    static final class Problem extends Exception {
        private static final long serialVersionUID = 1L;
        final int exit;

        Problem(String message, int exit) {
            super(message);
            this.exit = exit;
        }
    }

    static int run(String[] args) throws Problem {
        boolean natsOnly = false;
        boolean engineOnly = false;
        for (String arg : args) {
            switch (arg) {
                case "--nats" -> natsOnly = true;
                case "--engine" -> engineOnly = true;
                default -> throw new Problem("unknown option %s (known: --nats, --engine; with neither, both are started)".formatted(arg), 2);
            }
        }
        boolean wantNats = natsOnly || !engineOnly;
        boolean wantEngine = engineOnly || !natsOnly;
        Path home = MachineConfig.home();
        MachineConfig.Values config;
        try {
            config = MachineConfig.read(MachineConfig.file(home));
        } catch (MachineConfig.ConfigProblem e) {
            throw new Problem(e.getMessage() + ". Run: appmod setup", 1);
        }
        List<String> missing = MachineConfig.missingFiles(config);
        if (!missing.isEmpty()) {
            throw new Problem("%s no longer exist%s. Run: appmod setup".formatted(String.join(", ", missing), missing.size() == 1 ? "s" : ""), 1);
        }
        pidFile = home.resolve("run").resolve("nats.pid");
        removeStalePid();
        Runtime.getRuntime().addShutdownHook(new Thread(AppmodStart::stopStartedNats));

        int port = config.natsPort();
        Port state = probe(port);
        if (wantNats) {
            if (natsOnly) {
                return natsInForeground(config, state);
            }
            switch (state) {
                case NATS -> System.out.println("NATS already answers on 127.0.0.1:%d; it is used and left running when the engine ends.".formatted(port));
                case OTHER -> throw new Problem("port %d is open but it is not a NATS server; nothing was started. Stop what uses it, or change the port with appmod setup --nats-port".formatted(port), 1);
                case CLOSED -> startNatsInBackground(config, home);
            }
        } else if (state != Port.NATS) {
            System.out.println("note: no NATS server answers on 127.0.0.1:%d, so the engine will keep retrying until one does (appmod start --nats starts one).".formatted(port));
        }
        if (!wantEngine) {
            return 0;
        }
        try {
            return runEngine(config);
        } finally {
            stopStartedNats();
        }
    }

    // ---- NATS ---------------------------------------------------------------------------------------------------------------------

    /** Connects and reads the server's first line: a NATS server sends {@code INFO {...}} at once. */
    static Port probe(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(1500);
            byte[] buffer = new byte[64];
            int read = socket.getInputStream().read(buffer);
            String first = read > 0 ? new String(buffer, 0, read, StandardCharsets.UTF_8) : "";
            return first.startsWith("INFO ") ? Port.NATS : Port.OTHER;
        } catch (java.net.SocketTimeoutException _) {
            return Port.OTHER;
        } catch (IOException _) {
            return Port.CLOSED;
        }
    }

    static void startNatsInBackground(MachineConfig.Values config, Path home) throws Problem {
        Path log = home.resolve("run").resolve("nats.log");
        try {
            Files.createDirectories(log.getParent());
            ProcessBuilder builder = new ProcessBuilder(config.natsServer(), "-a", "127.0.0.1", "-p", String.valueOf(config.natsPort()))
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            startedNats = builder.start();
            Files.writeString(pidFile, String.valueOf(startedNats.pid()));
        } catch (IOException e) {
            throw new Problem("nats-server could not be started: " + e.getMessage(), 1);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (probe(config.natsPort()) == Port.NATS) {
                System.out.println("NATS started on 127.0.0.1:%d (log: %s)".formatted(config.natsPort(), log));
                return;
            }
            if (!startedNats.isAlive()) {
                break;
            }
            sleep(250);
        }
        String tail = lastLines(log, 15);
        stopStartedNats();
        throw new Problem("NATS did not answer on 127.0.0.1:%d within 15 seconds. The last lines of %s:\n%s".formatted(config.natsPort(), log, tail), 1);
    }

    static int natsInForeground(MachineConfig.Values config, Port state) throws Problem {
        if (state != Port.CLOSED) {
            throw new Problem("port %d is already taken%s; nothing was started".formatted(config.natsPort(), state == Port.NATS ? " by a NATS server (use it, or stop it first)" : ""), 1);
        }
        System.out.println("NATS listens on nats://127.0.0.1:%d (Ctrl+C stops it)".formatted(config.natsPort()));
        try {
            startedNats = new ProcessBuilder(config.natsServer(), "-a", "127.0.0.1", "-p", String.valueOf(config.natsPort())).inheritIO().start();
            Files.createDirectories(pidFile.getParent());
            Files.writeString(pidFile, String.valueOf(startedNats.pid()));
            int exit = startedNats.waitFor();
            return exit;
        } catch (IOException e) {
            throw new Problem("nats-server could not be started: " + e.getMessage(), 1);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            stopStartedNats();
        }
    }

    static void stopStartedNats() {
        Process process = startedNats;
        startedNats = null;
        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
        if (pidFile != null) {
            try {
                Files.deleteIfExists(pidFile);
            } catch (IOException _) {
                // a leftover pid file is removed by the next start
            }
        }
    }

    /** A process id file whose process is gone, or is not a nats-server, is removed; the process is never ended from it. */
    static void removeStalePid() {
        try {
            if (!Files.isRegularFile(pidFile)) {
                return;
            }
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            boolean isNats = handle.isPresent() && handle.get().isAlive()
                    && handle.get().info().command().map(c -> c.toLowerCase(Locale.ROOT).contains("nats")).orElse(false);
            if (!isNats) {
                Files.deleteIfExists(pidFile);
                System.out.println("removed a stale nats.pid (its process is gone)");
            }
        } catch (IOException | NumberFormatException _) {
            try {
                Files.deleteIfExists(pidFile);
            } catch (IOException _) {
                // nothing more can be done
            }
        }
    }

    // ---- the engine -------------------------------------------------------------------------------------------------------------

    static int runEngine(MachineConfig.Values config) throws Problem {
        Path java = Path.of(config.javaHome(), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar", config.engineJar()).inheritIO();
        builder.environment().put("SUBSCRIBER_ALLOWED_ROOTS", String.join(",", config.allowedRoots()).replace('\\', '/'));
        builder.environment().put("SUBSCRIBER_AGENT_TOOL", config.agentTool());
        builder.environment().put("SUBSCRIBER_DATA_DIR", config.dataDir().replace('\\', '/'));
        builder.environment().put("NATS_URL", "nats://127.0.0.1:" + config.natsPort());
        System.out.println("Starting the engine (folders: %s, agent: %s). Its console is this terminal; type exit to stop it.".formatted(String.join(", ", config.allowedRoots()), config.agentTool()));
        try {
            return builder.start().waitFor();
        } catch (IOException e) {
            throw new Problem("the engine could not be started: " + e.getMessage(), 1);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    static String lastLines(Path file, int count) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - count), lines.size()));
        } catch (IOException _) {
            return "(no log)";
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
