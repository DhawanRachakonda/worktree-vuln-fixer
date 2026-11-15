package dev.subscriber.console;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.nats.NatsMessageSource;
import dev.subscriber.worktree.GitPreflight;
import io.nats.client.Connection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Checks what the engine needs from its surroundings, one line per check: the database is open, NATS is connected, each allowed
 * root exists, the Copilot command is on the PATH, PowerShell is there on Windows, and the data directory can be written.
 * A line says pass or fail and why. No setting that can hold a secret is printed; the NATS line names the URL only.
 */
@Component
public class Doctor {

    /** One line of the report. */
    public record Check(String name, boolean passed, String detail) {

        String line() {
            return "%-6s%-22s%s".formatted(passed ? "PASS" : "FAIL", name, Text.printable(detail, 160));
        }
    }

    private final SubscriberProperties properties;
    private final Supplier<Boolean> databaseOpen;
    private final Supplier<Connection.Status> natsStatus;
    private final CommandFinder finder;
    private final boolean windows;
    private final GitPreflight preflight;

    @Autowired
    public Doctor(SubscriberProperties properties, JdbcClient jdbc, NatsMessageSource nats, GitPreflight preflight) {
        this(properties, () -> jdbc.sql("SELECT 1").query(Integer.class).single() == 1, nats::status,
                new CommandFinder(System.getenv("PATH"), isWindows(), System.getenv("PATHEXT")), isWindows(), preflight);
    }

    Doctor(SubscriberProperties properties, Supplier<Boolean> databaseOpen, Supplier<Connection.Status> natsStatus,
            CommandFinder finder, boolean windows) {
        this(properties, databaseOpen, natsStatus, finder, windows, null);
    }

    Doctor(SubscriberProperties properties, Supplier<Boolean> databaseOpen, Supplier<Connection.Status> natsStatus,
            CommandFinder finder, boolean windows, GitPreflight preflight) {
        this.preflight = preflight;
        this.properties = properties;
        this.databaseOpen = databaseOpen;
        this.natsStatus = natsStatus;
        this.finder = finder;
        this.windows = windows;
    }

    public List<Check> run() {
        List<Check> checks = new ArrayList<>();
        checks.add(database());
        checks.add(nats());
        for (String root : properties.allowedRoots()) {
            checks.add(allowedRoot(root));
        }
        checks.add(command(properties.agentTool().name().toLowerCase(java.util.Locale.ROOT) + " command", properties.agentCommand()));
        if (windows) {
            checks.add(command("powershell", "powershell.exe"));
        }
        checks.add(dataDirectory());
        checks.addAll(gitBlocks());
        return checks;
    }

    /**
     * One block for each repository a subject names (fixed {@code repository}) that works in a worktree: the git checks of the capability
     * git-preflight. A failing block never hides another item. A warning (something that only matters when publishing is on) passes and
     * says so. Repositories that are found from a report are checked by {@code publish}, when the report names one.
     */
    private List<Check> gitBlocks() {
        List<Check> blocks = new ArrayList<>();
        if (preflight == null) {
            return blocks;
        }
        properties.subjects().stream().filter(s -> s.repository() != null && s.isolation() == SubscriberProperties.Isolation.WORKTREE)
                .map(SubscriberProperties.Subject::repository).distinct().forEach(repository -> {
                    Path folder = Path.of(repository);
                    if (!Files.isDirectory(folder)) {
                        blocks.add(new Check("git " + folder.getFileName(), false, "%s does not exist or is not a folder".formatted(repository)));
                        return;
                    }
                    List<GitPreflight.Check> results = preflight.run(folder, null);
                    blocks.add(new Check("git " + folder.getFileName(), !GitPreflight.failed(results), repository));
                    for (GitPreflight.Check r : results) {
                        String detail = (r.status() == GitPreflight.Status.WARN ? "(warning) " : "") + r.detail()
                                + (r.consequence().isEmpty() ? "" : " - " + r.consequence());
                        blocks.add(new Check("  " + r.check(), r.status() != GitPreflight.Status.FAIL, detail));
                    }
                });
        return blocks;
    }

    /** The text the console prints. */
    public String report() {
        List<Check> checks = run();
        long failed = checks.stream().filter(c -> !c.passed()).count();
        String summary = failed == 0 ? "All %d checks passed.".formatted(checks.size())
                : "%d of %d checks failed.".formatted(failed, checks.size());
        return Stream.concat(checks.stream().map(Check::line), Stream.of(summary)).collect(Collectors.joining("\n"));
    }

    private Check database() {
        try {
            return Boolean.TRUE.equals(databaseOpen.get()) ? new Check("database", true, "open")
                    : new Check("database", false, "the test query returned nothing");
        } catch (RuntimeException e) {
            return new Check("database", false, "cannot be queried: " + e.getClass().getSimpleName());
        }
    }

    private Check nats() {
        Connection.Status status = natsStatus.get();
        String url = properties.nats().url();
        return status == Connection.Status.CONNECTED ? new Check("nats", true, "connected to %s".formatted(url))
                : new Check("nats", false, "%s, %s (messages published meanwhile are lost; the engine keeps trying)"
                        .formatted(status.name().toLowerCase(Locale.ROOT), url));
    }

    private Check allowedRoot(String root) {
        boolean exists = Files.isDirectory(Path.of(root));
        return new Check("allowed root", exists, exists ? root : "%s does not exist or is not a folder".formatted(root));
    }

    private Check command(String name, String command) {
        Optional<Path> found = finder.find(command);
        return found.map(path -> new Check(name, true, path.toString()))
                .orElseGet(() -> new Check(name, false, "%s was not found on the PATH".formatted(command)));
    }

    private Check dataDirectory() {
        Path directory = Path.of(properties.dataDir());
        try {
            Files.createDirectories(directory);
            Path probe = Files.createTempFile(directory, "doctor-", ".tmp");
            Files.delete(probe);
            return new Check("data directory", true, "%s is writable".formatted(directory.toAbsolutePath()));
        } catch (IOException | RuntimeException e) {
            return new Check("data directory", false,
                    "%s is not writable: %s".formatted(directory.toAbsolutePath(), e.getClass().getSimpleName()));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
