package dev.subscriber.nats;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Hands one report to the engine through NATS, from the console: the message body is the absolute path of the report, on one of the
 * subjects the engine listens to. It uses the engine's own NATS settings and a connection of its own that ends with the call, so the
 * message goes through the same server and the same intake as a message from any other publisher.
 *
 * <p>It refuses before connecting when the subject is not one of the configured ones or the path cannot be a report. Whether the report
 * lies inside an allowed root and has a repository around it is the intake's decision, as for every message.
 */
@Component
public class ReportPublisher {

    /** Opens a connection from options; the real client by default, a stand-in in tests. */
    @FunctionalInterface
    interface Connector {
        Connection connect(Options options) throws IOException, InterruptedException;
    }

    /** What happened: {@code published} is true only when the message was handed to the server. */
    public record Result(boolean published, String message) {
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration FLUSH_TIMEOUT = Duration.ofSeconds(5);

    private final SubscriberProperties properties;
    private final Connector connector;

    @Autowired
    public ReportPublisher(SubscriberProperties properties) {
        this(properties, Nats::connect);
    }

    ReportPublisher(SubscriberProperties properties, Connector connector) {
        this.properties = properties;
        this.connector = connector;
    }

    /**
     * @param subjectName the subject to publish on, or null for the first configured subject
     * @param reportPath  the report; a relative path is made absolute
     */
    public Result publish(String subjectName, String reportPath) {
        List<String> known = properties.subjects().stream().map(Subject::subject).toList();
        String subject = subjectName == null || subjectName.isBlank() ? (known.isEmpty() ? null : known.getFirst()) : subjectName.strip();
        if (subject == null || !known.contains(subject)) {
            return refused("'%s' is not a subject this engine listens to. Configured: %s.".formatted(
                    NatsLogging.printable(subject == null ? "" : subject), String.join(", ", known)));
        }
        Path report;
        try {
            report = absoluteReport(reportPath);
        } catch (IllegalArgumentException e) {
            return refused(e.getMessage());
        }
        return send(subject, report);
    }

    private static Path absoluteReport(String reportPath) {
        if (reportPath == null || reportPath.isBlank()) {
            throw new IllegalArgumentException("Give the path of the report to publish.");
        }
        if (reportPath.indexOf('\0') >= 0 || reportPath.indexOf('\n') >= 0 || reportPath.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("The path must be a single path without line breaks or null characters.");
        }
        Path path;
        try {
            path = Path.of(reportPath).toAbsolutePath().normalize();
        } catch (InvalidPathException _) {
            throw new IllegalArgumentException("That is not a valid path.");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("The report does not exist: " + NatsLogging.printable(path.toString()));
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("The report is not a file: " + NatsLogging.printable(path.toString()));
        }
        return path;
    }

    private Result send(String subject, Path report) {
        Options options = NatsOptionsFactory.builder(properties.nats(), new NatsLogging(), new NatsLogging())
                .connectionName("subscriber-engine-publish").maxReconnects(0).connectionTimeout(CONNECT_TIMEOUT).build();
        try (Connection connection = connector.connect(options)) {
            connection.publish(subject, report.toString().getBytes(StandardCharsets.UTF_8));
            connection.flush(FLUSH_TIMEOUT);
            return new Result(true, "Published to %s: %s".formatted(subject, NatsLogging.printable(report.toString())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return refused("Interrupted before the message was sent.");
        } catch (IOException | java.util.concurrent.TimeoutException | RuntimeException e) {
            // The exception text can name the server; the credentials are never part of it, but only the URL is shown here anyway.
            return refused("NATS could not be reached at %s, so nothing was published.".formatted(properties.nats().url()));
        }
    }

    private static Result refused(String message) {
        return new Result(false, message);
    }
}
