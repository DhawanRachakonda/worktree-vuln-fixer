package dev.subscriber.console;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.nats.ReportPublisher;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionState;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/** The operator console's commands. Most only read the session records; {@code reopen}, {@code cleanup} and {@code publish} act, each on request. */
@Component
public class ConsoleCommands {

    private static final int LIST_LIMIT = 20;

    private final FixSessionRepository sessions;
    private final SubscriberProperties properties;
    private final Environment environment;
    private final Doctor doctor;
    private final ReopenService reopenService;
    private final CleanupService cleanupService;
    private final ReportPublisher publisher;
    private final dev.subscriber.worktree.BranchPublisher branchPublisher;

    public ConsoleCommands(FixSessionRepository sessions, SubscriberProperties properties, Environment environment, Doctor doctor,
            ReopenService reopenService, CleanupService cleanupService, ReportPublisher publisher,
            dev.subscriber.worktree.BranchPublisher branchPublisher) {
        this.branchPublisher = branchPublisher;
        this.sessions = sessions;
        this.properties = properties;
        this.environment = environment;
        this.doctor = doctor;
        this.reopenService = reopenService;
        this.cleanupService = cleanupService;
        this.publisher = publisher;
    }

    @Command(name = "sessions", description = "List the newest sessions, optionally only those in one state")
    public void sessions(CommandContext context,
            @Option(longName = "state", description = "Only sessions in this state") String state) {
        PrintWriter out = context.outputWriter();
        SessionState filter = parse(state);
        if (state != null && filter == null) {
            out.println("Unknown state '%s'. Use one of %s.".formatted(Text.printable(state, 40), Arrays.toString(SessionState.values())));
            return;
        }
        out.println(ConsoleReports.sessions(sessions.list(filter, LIST_LIMIT, 0), sessions.count(filter), filter,
                s -> s.state() == SessionState.QUEUED ? sessions.blockerOf(s).map(FixSession::id).orElse(null) : null));
    }

    @Command(name = "session", description = "Show one session")
    public void session(CommandContext context, @Argument(index = 0, description = "The session id") String id) {
        PrintWriter out = context.outputWriter();
        FixSession found = find(id);
        out.println(found == null ? notFound(id) : ConsoleReports.session(found,
                found.state() == SessionState.QUEUED ? sessions.blockerOf(found).orElse(null) : null));
    }

    @Command(name = "events", description = "Show the states a session went through")
    public void events(CommandContext context, @Argument(index = 0, description = "The session id") String id) {
        PrintWriter out = context.outputWriter();
        FixSession found = find(id);
        out.println(found == null ? notFound(id) : ConsoleReports.events(sessions.events(found.id())));
    }

    @Command(name = "config", description = "Show the settings in force (secrets are never shown)")
    public void config(CommandContext context) {
        context.outputWriter().println(ConsoleReports.config(properties, environment.getProperty("server.address")));
    }

    @Command(name = "doctor", description = "Check the database, NATS, the allowed roots, the Copilot command and the data directory")
    public void doctor(CommandContext context) {
        context.outputWriter().println(doctor.report());
    }

    @Command(name = "reopen", description = "Open a window that resumes a finished session, to continue it by hand")
    public void reopen(CommandContext context, @Argument(index = 0, description = "The session id") String id) {
        context.outputWriter().println(reopenService.reopen(id).message());
    }

    @Command(name = "cleanup", description = "Remove the worktree folder of a finished session (the branch is kept); without --yes only says what it would do")
    public void cleanup(CommandContext context, @Argument(index = 0, description = "The session id") String id,
            @Option(longName = "yes", description = "Really remove the folder", defaultValue = "false") boolean yes) {
        context.outputWriter().println(cleanupService.cleanup(id, yes).message());
    }

    @Command(name = "publish", description = "Publish a report to NATS so the engine records a session for it (first configured subject unless --subject is given)")
    public void publish(CommandContext context, @Argument(index = 0, description = "The path of the report file") String report,
            @Option(longName = "subject", description = "The subject to publish on") String subject,
            @Option(longName = "source-branch", description = "The branch the fixes are based on (default: the saved one, else the repository's default branch)") String sourceBranch,
            @Option(longName = "unsafe-local-changes", description = "Base the session on the checkout's uncommitted and untracked changes (cannot be combined with --source-branch)", defaultValue = "false") boolean localChanges) {
        context.outputWriter().println(publisher.publish(subject, report, sourceBranch, localChanges).message());
    }

    @Command(name = "publish-branch", description = "Push the branch of a finished session (and run the pull-request command in pull-request mode); needed for a session that included local changes. Without --yes only says what it would do")
    public void publishBranch(CommandContext context, @Argument(index = 0, description = "The session id") String id,
            @Option(longName = "yes", description = "Really push", defaultValue = "false") boolean yes) {
        PrintWriter out = context.outputWriter();
        FixSession found = find(id);
        String refusal = branchPublisher.refusal(found);
        if (found == null || refusal != null) {
            out.println(found == null ? notFound(id) : "Nothing was pushed: " + refusal + ".");
            return;
        }
        if (!yes) {
            out.println("Would push the branch %s of session %s to origin%s. Run again with --yes to do it.".formatted(
                    Text.printable(found.workBranch()), found.id(), found.localChanges()
                            ? " (it holds a snapshot of uncommitted local work, possibly including untracked files: look at it first)" : ""));
            return;
        }
        out.println(branchPublisher.confirmed(found).message());
    }

    private FixSession find(String id) {
        // Only a UUID can name a session; anything else is not looked up.
        return id != null && id.matches("[0-9a-fA-F-]{36}") ? sessions.find(id.toLowerCase(Locale.ROOT)).orElse(null) : null;
    }

    private static String notFound(String id) {
        return "No session '%s'.".formatted(Text.printable(id, 40));
    }

    private static SessionState parse(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return SessionState.valueOf(state.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
