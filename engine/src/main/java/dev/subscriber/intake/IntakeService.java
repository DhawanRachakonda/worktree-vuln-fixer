package dev.subscriber.intake;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeChecker.Accepted;
import dev.subscriber.intake.IntakeChecker.Outcome;
import dev.subscriber.intake.IntakeChecker.Rejected;
import dev.subscriber.lifecycle.SessionQueued;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.session.PromptWriter;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one message into a record. It never launches anything: an accepted message becomes a {@code QUEUED} session, a refused
 * one a {@code REJECTED} session that says why, and a message on any other subject is ignored. The record and its first event are
 * written in one transaction, before any launch can happen.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final SubscriberProperties properties;
    private final IntakeChecker checker;
    private final FixSessionRepository sessions;
    private final SessionFolders folders;
    private final PromptWriter prompts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public IntakeService(SubscriberProperties properties, IntakeChecker checker, FixSessionRepository sessions,
            SessionFolders folders, PromptWriter prompts, ApplicationEventPublisher events, Clock clock) {
        this.properties = properties;
        this.checker = checker;
        this.sessions = sessions;
        this.folders = folders;
        this.prompts = prompts;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @return the id of the new session, or empty when the subject is not an issue subject (nothing is recorded)
     */
    @Transactional
    public Optional<String> accept(String subjectName, String payload) {
        return accept(subjectName, payload, List.of(), List.of());
    }

    /**
     * As above, with the values of the message headers {@code source-branch} and {@code unsafe-local-changes} (empty lists when absent).
     * Headers are data: a value is accepted only as a valid branch name or the text {@code true}, and a message with an invalid or
     * repeated header, or with both, is recorded as rejected.
     */
    @Transactional
    public Optional<String> accept(String subjectName, String payload, List<String> sourceBranchHeader, List<String> localChangesHeader) {
        Optional<Subject> subject = properties.subjects().stream().filter(s -> s.subject().equals(subjectName)).findFirst();
        if (subject.isEmpty()) {
            log.info("Ignoring a message on the unsupported subject {}", printable(subjectName));
            return Optional.empty();
        }
        Subject s = subject.get();
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        SourceHeaders.Parsed headers = SourceHeaders.parse(sourceBranchHeader, localChangesHeader);
        if (headers.problem() != null) {
            reject(id, s, payload, headers.problem(), now);
            return Optional.of(id);
        }
        Outcome outcome = checker.check(s, payload, headers.branch());
        if (outcome instanceof Accepted ok) {
            try {
                queue(id, s, ok, headers, now);
                return Optional.of(id);
            } catch (IOException e) {
                deleteQuietly(folders.folder(id));
                outcome = new Rejected("the report could not be copied: " + e.getMessage());
            } catch (RuntimeException e) {
                deleteQuietly(folders.folder(id));
                throw e;
            }
        }
        reject(id, s, payload, ((Rejected) outcome).reason(), now);
        return Optional.of(id);
    }

    /** The agent works from a copy of the report and a prompt file, both made now, before the record exists. */
    private void queue(String id, Subject s, Accepted ok, SourceHeaders.Parsed headers, Instant now) throws IOException {
        Path snapshot = folders.snapshot(id, ok.report());
        prompts.write(id, s, snapshot, ok.report(), ok.repository());
        sessions.insert(new NewSession(id, SessionState.QUEUED, s.subject(), s.source(), ok.report().toString(),
                ok.repository().toString(), s.agent(), engine(), now, null, headers.branch(), headers.localChanges()));
        sessions.setSnapshot(id, snapshot.toString());
        sessions.appendEvent(id, now, SessionState.QUEUED, "received");
        if (headers.branch() != null) {
            sessions.appendEvent(id, now, SessionState.QUEUED, "source branch requested: " + headers.branch());
        }
        if (headers.localChanges()) {
            sessions.appendEvent(id, now, SessionState.QUEUED, "local changes of the checkout requested (unsafe-local-changes)");
        }
        log.info("Session {} queued for {} (repository {})", id, s.subject(), printable(ok.repository().toString()));
        // Delivered after the transaction commits, so the dispatcher finds the record.
        events.publishEvent(new SessionQueued(id));
    }

    /** The coding tool that runs the sessions now: {@code copilot} or {@code claude}. */
    private String engine() {
        return properties.agentTool().name().toLowerCase(java.util.Locale.ROOT);
    }

    private void reject(String id, Subject s, String payload, String reason, Instant now) {
        sessions.insert(new NewSession(id, SessionState.REJECTED, s.subject(), s.source(), payload == null ? "" : payload, null,
                s.agent(), engine(), now, reason));
        sessions.appendEvent(id, now, SessionState.REJECTED, reason);
        log.warn("Session {} rejected on {}: {}", id, s.subject(), reason);
    }

    private static void deleteQuietly(Path folder) {
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException _) {
            // nothing to clean up, or it is cleaned on the next attempt
        }
    }

    /** Text from a message, made safe for a log line: control characters cannot forge or hide log entries. */
    static String printable(String text) {
        if (text == null) {
            return "(none)";
        }
        String shown = text.chars().limit(300)
                .mapToObj(c -> Character.isISOControl(c) ? "\\u%04x".formatted(c) : Character.toString(c))
                .collect(Collectors.joining());
        return text.length() > 300 ? shown + "..." : shown;
    }
}
