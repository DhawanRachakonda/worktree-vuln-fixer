package dev.subscriber.intake;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.store.FixSessionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Checks one message before anything is launched. Every path is resolved to its real location (which removes {@code ..}
 * segments and follows links and junctions), so an allowed-roots check cannot be fooled by a path that merely looks inside.
 *
 * <p>A message passes only if its payload is one absolute path of an existing regular file inside an allowed root, the
 * repository to work in can be found and is a git repository inside an allowed root, and no queued or running session already
 * works on the same report.
 */
@Component
public class IntakeChecker {

    /** The result of checking one message. */
    public sealed interface Outcome permits Accepted, Rejected {
    }

    /** The message is fine: the real report path and the real repository path. */
    public record Accepted(Path report, Path repository) implements Outcome {
    }

    /** The message failed a check; {@code reason} is shown to the operator and stored. */
    public record Rejected(String reason) implements Outcome {
    }

    private final List<Path> allowedRoots;
    private final FixSessionRepository sessions;

    public IntakeChecker(SubscriberProperties properties, FixSessionRepository sessions) {
        this.allowedRoots = properties.allowedRoots().stream().map(IntakeChecker::realRoot).toList();
        this.sessions = sessions;
    }

    private static Path realRoot(String root) {
        try {
            return Path.of(root).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("subscriber.allowed-roots entry cannot be resolved: " + root, e);
        }
    }

    public Outcome check(Subject subject, String payload) {
        Path report;
        try {
            report = realReport(payload);
        } catch (Refusal r) {
            return new Rejected(r.getMessage());
        }
        Path repository;
        try {
            repository = repositoryFor(subject, report);
        } catch (Refusal r) {
            return new Rejected(r.getMessage());
        }
        if (sessions.hasActiveForReport(report.toString())) {
            return new Rejected("duplicate: a queued or running session already works on this report");
        }
        return new Accepted(report, repository);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The report
    // ---------------------------------------------------------------------------------------------------------------

    private Path realReport(String payload) {
        if (payload == null || payload.isEmpty()) {
            throw new Refusal("the message is empty; it must be the path of an issue report");
        }
        if (payload.indexOf('\0') >= 0 || payload.indexOf('\n') >= 0 || payload.indexOf('\r') >= 0) {
            throw new Refusal("the message must be a single path without line breaks or null characters");
        }
        Path path;
        try {
            path = Path.of(payload);
        } catch (InvalidPathException _) {
            throw new Refusal("the message is not a valid path");
        }
        if (!path.isAbsolute()) {
            throw new Refusal("the report path must be absolute");
        }
        if (!Files.exists(path)) {
            throw new Refusal("the report does not exist");
        }
        if (!Files.isRegularFile(path)) {
            throw new Refusal("the report path is not a regular file");
        }
        Path real = real(path, "the report");
        requireInsideRoots(real, "the report");
        return real;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The repository
    // ---------------------------------------------------------------------------------------------------------------

    private Path repositoryFor(Subject subject, Path report) {
        Path candidate;
        if (subject.reportLocation() != null) {
            candidate = repositoryFromReportLocation(subject.reportLocation(), report);
        } else if (subject.repository() != null) {
            candidate = real(Path.of(subject.repository()), "the configured repository");
        } else {
            throw new Refusal("no repository is configured for this subject, so the repository could not be determined");
        }
        if (!Files.isDirectory(candidate)) {
            throw new Refusal("the repository is not a directory");
        }
        if (!Files.exists(candidate.resolve(".git"))) {
            throw new Refusal("the repository could not be determined: it holds no .git entry, so it is not a git repository");
        }
        requireInsideRoots(candidate, "the repository");
        return candidate;
    }

    /** The repository is the report's location with the subject's relative report location removed from its end. */
    private static Path repositoryFromReportLocation(String reportLocation, Path report) {
        int segments = reportLocation.split("/").length;
        Path candidate = report;
        for (int i = 0; i < segments; i++) {
            candidate = candidate.getParent();
            if (candidate == null) {
                throw new Refusal("the repository could not be determined: the report is not at <repository>/" + reportLocation);
            }
        }
        try {
            if (!candidate.resolve(reportLocation).toRealPath().equals(report)) {
                throw new Refusal("the repository could not be determined: the report is not at <repository>/" + reportLocation);
            }
        } catch (IOException _) {
            throw new Refusal("the repository could not be determined: the report is not at <repository>/" + reportLocation);
        }
        return candidate;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------------------------------

    private static Path real(Path path, String what) {
        try {
            return path.toRealPath();
        } catch (IOException _) {
            throw new Refusal("%s cannot be resolved".formatted(what));
        }
    }

    private void requireInsideRoots(Path real, String what) {
        if (allowedRoots.stream().noneMatch(real::startsWith)) {
            throw new Refusal("%s is outside the allowed roots".formatted(what));
        }
    }

    /** A failed check; its message becomes the rejection reason. */
    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refusal(String message) {
            super(message, null, false, false);
        }
    }
}
