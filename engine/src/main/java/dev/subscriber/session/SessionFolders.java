package dev.subscriber.session;

import dev.subscriber.config.SubscriberProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The folder of each session under {@code <data dir>/sessions/<session id>/}: the snapshot of the report, the prompt, the launcher
 * script, the logs and the exit record. The command that opens a session names only paths inside this folder, which the engine
 * builds from validated values, so nothing from a message ever reaches a command line.
 */
@Component
public class SessionFolders {

    /** Reports larger than this are refused instead of copied. */
    static final long MAX_REPORT_BYTES = 20L * 1024 * 1024;

    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern UNSAFE_NAME_CHARACTER = Pattern.compile("[^A-Za-z0-9._ -]");

    private final Path sessionsRoot;

    public SessionFolders(SubscriberProperties properties) {
        this.sessionsRoot = Path.of(properties.dataDir()).toAbsolutePath().normalize().resolve("sessions");
    }

    /** The folder of a session; the id must be a UUID, which is what keeps the path free of anything unexpected. */
    public Path folder(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("not a session id: " + sessionId);
        }
        return sessionsRoot.resolve(sessionId);
    }

    public Path create(String sessionId) throws IOException {
        Path folder = folder(sessionId);
        Files.createDirectories(folder.resolve("report"));
        Files.createDirectories(folder.resolve("logs"));
        return folder;
    }

    public Path promptFile(String sessionId) {
        return folder(sessionId).resolve("prompt.md");
    }

    /**
     * Copies the report into the session folder, byte for byte. The agent reads this copy, so a new audit that overwrites the original
     * cannot change a session that is waiting or running.
     */
    public Path snapshot(String sessionId, Path report) throws IOException {
        long size = Files.size(report);
        if (size > MAX_REPORT_BYTES) {
            throw new IOException("the report is larger than %d MiB".formatted(MAX_REPORT_BYTES / (1024 * 1024)));
        }
        Path target = create(sessionId).resolve("report").resolve(safeName(report.getFileName().toString()));
        Files.copy(report, target);
        return target;
    }

    /** A file name made of letters, digits and a few punctuation marks; anything else becomes an underscore. */
    static String safeName(String name) {
        String cleaned = UNSAFE_NAME_CHARACTER.matcher(name).replaceAll("_");
        if (cleaned.length() > 100) {
            cleaned = cleaned.substring(cleaned.length() - 100);
        }
        return cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '.') ? "report" : cleaned;
    }
}
