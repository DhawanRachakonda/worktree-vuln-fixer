package dev.subscriber.nats;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * A real NATS server for the opt-in live tests, started and stopped by the test. By default it runs in a Docker container
 * (image {@value #IMAGE}, published on a free loopback port), so nothing has to be installed. A {@code nats-server} program named
 * by {@code NATS_SERVER_EXE} is used instead when that variable is set. When neither Docker nor the program is available the test is
 * skipped, with the reason. Override the image with {@code NATS_DOCKER_IMAGE}.
 */
final class NatsTestServer implements AutoCloseable {

    static final String IMAGE = "nats:2.10-alpine";

    private final int port;
    private final Process process;
    private final String container;

    private NatsTestServer(int port, Process process, String container) {
        this.port = port;
        this.process = process;
        this.container = container;
    }

    int port() {
        return port;
    }

    /** Starts a server with extra {@code nats-server} arguments (for example {@code --auth <token>}); skips the test when it cannot. */
    static NatsTestServer start(Path logFolder, String... arguments) throws IOException {
        Assumptions.assumeTrue("true".equals(System.getProperty("nats.tests")), "opt-in: run with -Pnats-tests");
        String exe = System.getenv("NATS_SERVER_EXE");
        NatsTestServer server;
        if (exe != null && !exe.isBlank() && Files.isExecutable(Path.of(exe))) {
            server = fromProgram(exe, logFolder, arguments);
        } else {
            Assumptions.assumeTrue(dockerWorks(), "no Docker daemon answered and NATS_SERVER_EXE is not set: start Docker (or set NATS_SERVER_EXE) to run the live tests");
            server = fromDocker(arguments);
        }
        try {
            await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() -> {
            try (Socket s = new Socket("127.0.0.1", server.port)) {
                return s.isConnected();
            }
        });
        } catch (RuntimeException e) {
            try {
                server.close();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
        return server;
    }

    private static NatsTestServer fromProgram(String exe, Path logFolder, String... arguments) throws IOException {
        int port = freePort();
        List<String> command = new ArrayList<>(List.of(exe, "-a", "127.0.0.1", "-p", String.valueOf(port)));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFolder.resolve("server.log").toFile()).start();
        return new NatsTestServer(port, process, null);
    }

    private static NatsTestServer fromDocker(String... arguments) throws IOException {
        String image = System.getenv("NATS_DOCKER_IMAGE") == null || System.getenv("NATS_DOCKER_IMAGE").isBlank() ? IMAGE : System.getenv("NATS_DOCKER_IMAGE");
        List<String> run = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "-p", "127.0.0.1::4222", image));
        run.addAll(List.of(arguments));
        Output started = docker(300, run.toArray(String[]::new));
        if (started.exit != 0) {
            Assumptions.abort("docker could not start the NATS server (" + started.text.lines().reduce((a, b) -> b).orElse("") + ")");
        }
        String id = started.text.lines().reduce((a, b) -> b).orElse("").strip();
        try {
            Output mapped = docker(30, "docker", "port", id, "4222/tcp");
            String line = mapped.text.lines().findFirst().orElse("");
            return new NatsTestServer(Integer.parseInt(line.substring(line.lastIndexOf(':') + 1).strip()), null, id);
        } catch (IOException | RuntimeException e) {
            // the container exists but nobody holds a handle to it: it must not be left running
            docker(60, "docker", "rm", "-f", id);
            throw e;
        }
    }

    private record Output(int exit, String text) {
    }

    private static Output docker(long seconds, String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Output(-1, "docker did not finish in " + seconds + " seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Output(-1, "interrupted");
        }
        return new Output(process.exitValue(), text);
    }

    private static boolean dockerWorks() {
        try {
            return docker(20, "docker", "info", "--format", "{{.ServerVersion}}").exit == 0;
        } catch (IOException _) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket free = new ServerSocket(0)) {
            return free.getLocalPort();
        }
    }

    @Override
    public void close() throws InterruptedException {
        if (process != null) {
            process.destroyForcibly().waitFor();
        }
        if (container != null) {
            try {
                docker(60, "docker", "rm", "-f", container);
            } catch (IOException _) {
                // the container is started with --rm and ends with the daemon anyway
            }
        }
    }
}
