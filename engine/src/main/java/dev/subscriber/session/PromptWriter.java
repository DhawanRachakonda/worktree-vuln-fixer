package dev.subscriber.session;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Runner;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.store.Worktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Writes {@code prompt.md} of a session from a template: {@code prompts/<subject>.md} when the subject has its own, else
 * {@code prompts/default.md}. The agent is told to read this file, so the paths (which come from a message) live in a file, in code
 * spans, and never on a command line.
 */
@Component
public class PromptWriter {

    static final String QUESTIONS_WINDOW = """
            A person is at the keyboard in this window: when a decision is genuinely ambiguous, ask them \
            and wait for the answer.""";
    static final String QUESTIONS_HEADLESS = """
            Nobody is available to answer questions: when a decision is ambiguous, choose the most \
            conservative option consistent with these rules and record it under `assumptions` in the report.""";

    private final SessionFolders folders;
    private final SubscriberProperties properties;

    public PromptWriter(SessionFolders folders, SubscriberProperties properties) {
        this.folders = folders;
        this.properties = properties;
    }

    /** For a session that works in the repository's own checkout. */
    public Path write(String sessionId, Subject subject, Path snapshot, Path originalReport, Path repository) throws IOException {
        return write(sessionId, subject, snapshot, originalReport, repository, null);
    }

    /**
     * @param repository the person's checkout
     * @param worktree   the session's worktree, or null: then the agent works in {@code repository}; otherwise the worktree is "the
     *                   repository" of the task and the checkout is named as not to be changed
     */
    public Path write(String sessionId, Subject subject, Path snapshot, Path originalReport, Path repository, Worktree worktree)
            throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("sessionId", sessionId);
        values.put("source", subject.source());
        values.put("repository", code(worktree == null ? repository.toString() : worktree.path()));
        values.put("workspace", worktree == null ? "" : """
                This is a git worktree on the branch %s, a separate copy of the repository: commit your work on that branch. \
                The person's own checkout is at %s; do not change anything there.""".formatted(code(worktree.branch()), code(repository.toString())));
        values.put("report", code(snapshot.toString()));
        values.put("originalReport", code(originalReport.toString()));
        values.put("questions", properties.effectiveRunner() == Runner.WINDOW ? QUESTIONS_WINDOW : QUESTIONS_HEADLESS);

        String text = Templates.fill(template(subject.subject()), values);
        Path file = folders.promptFile(sessionId);
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static String template(String subject) {
        Resource specific = new ClassPathResource("prompts/%s.md".formatted(subject));
        Resource resource = specific.exists() ? specific : new ClassPathResource("prompts/default.md");
        try {
            return new String(resource.getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the prompt template " + resource, e);
        }
    }

    /** A value shown as a Markdown code span; a backtick inside it cannot close the span early. */
    static String code(String value) {
        return "`" + value.replace('`', '\'') + "`";
    }
}
