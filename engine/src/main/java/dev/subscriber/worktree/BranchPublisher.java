package dev.subscriber.worktree;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.PublishMode;
import dev.subscriber.console.CommandFinder;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Pushes a finished session's own branch to {@code origin} and, in pull-request mode, runs the operator's command template. Only
 * {@code git} is used by the engine; hosts differ only in the template. The agent is never involved: this runs after the session
 * has ended, with git's own credentials, without a prompt, within a time limit, and never with force.
 *
 * <p>A failed push or command never changes the session's final state: it is an event and a field.
 */
@Component
public class BranchPublisher {

    private static final Logger log = LoggerFactory.getLogger(BranchPublisher.class);
    private static final Pattern LINK = Pattern.compile("https?://[^\\s<>\"']+");
    private static final Pattern USER_INFO = Pattern.compile("(?i)(\\b[a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final long COMMAND_SECONDS = 120;

    public static final String NONE = "none";
    public static final String AWAITING = "awaiting_confirmation";
    public static final String PUSHED = "pushed";
    public static final String PUSH_FAILED = "push_failed";

    /** What was done for a session, in one line for the console. */
    public record Outcome(String publishState, String pullRequestState, String pullRequestLink, String message) {
    }

    private final SubscriberProperties properties;
    private final FixSessionRepository sessions;
    private final SessionFolders folders;
    private final Clock clock;
    private final GitRunner git;
    private final CommandFinder finder;

    @Autowired
    public BranchPublisher(SubscriberProperties properties, FixSessionRepository sessions, SessionFolders folders, Clock clock) {
        this(properties, sessions, folders, clock, new GitRunner(WorktreeService.emptyHooksFolder(properties), 120), null);
    }

    BranchPublisher(SubscriberProperties properties, FixSessionRepository sessions, SessionFolders folders, Clock clock, GitRunner git,
            CommandFinder finder) {
        this.properties = properties;
        this.sessions = sessions;
        this.folders = folders;
        this.clock = clock;
        this.git = git;
        boolean onWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.finder = finder != null ? finder : new CommandFinder(System.getenv("PATH"), onWindows, System.getenv("PATHEXT"));
    }

    /** The mode in force for a repository: its own setting, else the engine's. A setting that cannot be read counts as none. */
    public PublishMode modeFor(Path repository) {
        RepositorySettings.Read read = RepositorySettings.read(repository);
        if (!read.ok()) {
            return PublishMode.NONE;
        }
        return read.settings().mode() != null ? read.settings().mode() : properties.publish().mode();
    }

    /** The command template in force for a repository: its own setting, else the engine's; null when none. */
    String commandFor(Path repository) {
        RepositorySettings.Read read = RepositorySettings.read(repository);
        if (read.ok() && read.settings().pullRequestCommand() != null) {
            return read.settings().pullRequestCommand();
        }
        return properties.publish().pullRequestCommand();
    }

    /** Called once after a session reached a final state. Does nothing for a state that does not publish. */
    public Optional<Outcome> afterSession(String sessionId) {
        FixSession session = sessions.find(sessionId).orElse(null);
        if (session == null || !eligibleState(session) || !hasCommits(session)) {
            return Optional.empty();
        }
        PublishMode mode = modeFor(Path.of(session.repositoryPath()));
        if (mode == PublishMode.NONE) {
            sessions.setPublishState(sessionId, NONE);
            return Optional.empty();
        }
        if (session.localChanges()) {
            sessions.setPublishState(sessionId, AWAITING);
            event(session, "not published: this session started from a snapshot of uncommitted local work, so the branch %s waits for your confirmation (publish-branch %s --yes)"
                    .formatted(session.workBranch(), session.id()));
            return Optional.of(new Outcome(AWAITING, null, null, "awaiting confirmation"));
        }
        return Optional.of(publish(session, mode));
    }

    /** The operator confirmed (or retries) a session: pushes it whatever the local-changes rule, in the mode in force or at least {@code push}. */
    public Outcome confirmed(FixSession session) {
        PublishMode mode = modeFor(Path.of(session.repositoryPath()));
        return publish(session, mode == PublishMode.NONE ? PublishMode.PUSH : mode);
    }

    /** Why a session cannot be published by hand, or null when it can. */
    public String refusal(FixSession session) {
        if (session == null) {
            return "there is no such session";
        }
        if (!eligibleState(session)) {
            return "the session is %s: only a session that ended DONE or PARTIAL can be published".formatted(session.state());
        }
        if (!hasCommits(session)) {
            return "the session made no commits, so there is nothing to push";
        }
        return null;
    }

    private static boolean eligibleState(FixSession session) {
        return session.hasWorktree() && (session.state() == SessionState.DONE || session.state() == SessionState.PARTIAL);
    }

    private static boolean hasCommits(FixSession session) {
        return session.fixCommits() != null && session.fixCommits() > 0;
    }

    private Outcome publish(FixSession session, PublishMode mode) {
        Path work = Path.of(session.workPath());
        String branch = session.workBranch();
        GitRunner.Result pushed;
        try {
            pushed = git.run(work, Map.of("GCM_INTERACTIVE", "never"), "push", "origin",
                    "refs/heads/%s:refs/heads/%s".formatted(branch, branch));
        } catch (IOException e) {
            pushed = new GitRunner.Result(1, e.getMessage());
        }
        if (!pushed.ok()) {
            sessions.setPublishState(session.id(), PUSH_FAILED);
            String reason = reasonOf(pushed);
            event(session, "push failed: %s. The local branch %s is untouched; retry with: publish-branch %s --yes (or by hand: git -C \"%s\" push origin %s)"
                    .formatted(reason, branch, session.id(), work, branch));
            return new Outcome(PUSH_FAILED, null, null, "push failed: " + reason);
        }
        sessions.setPublishState(session.id(), PUSHED);
        event(session, "pushed %s to origin".formatted(branch));
        if (mode != PublishMode.PULL_REQUEST) {
            return new Outcome(PUSHED, null, null, "pushed " + branch);
        }
        return pullRequest(session, work);
    }

    private Outcome pullRequest(FixSession session, Path work) {
        String branch = session.workBranch();
        String base = baseOf(session, work);
        String template = commandFor(Path.of(session.repositoryPath()));
        if (template == null) {
            return skipped(session, "no pull-request command is configured: open a request from %s to %s by hand".formatted(branch, base));
        }
        PullRequestCommand.Parsed parsed = PullRequestCommand.parse(template);
        if (!parsed.ok()) {
            return skipped(session, "pull request not created: " + parsed.problem());
        }
        Optional<Path> program = finder.find(parsed.program());
        if (program.isEmpty()) {
            return skipped(session, "pull request not created: the program %s is not on the PATH; the branch %s is pushed, open a request to %s by hand"
                    .formatted(parsed.program(), branch, base));
        }
        Path bodyFile;
        try {
            bodyFile = Files.writeString(Files.createDirectories(folders.folder(session.id())).resolve("pull-request.md"),
                    description(session, base), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return skipped(session, "pull request not created: the description could not be written: " + e.getMessage());
        }
        List<String> command = new ArrayList<>(PullRequestCommand.fill(parsed.arguments(), Map.of("base", base, "head", branch,
                "title", title(session), "bodyFile", bodyFile.toString())));
        command.set(0, program.get().toString());
        try {
            Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
            process.getOutputStream().close();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readNBytes(64 * 1024), StandardCharsets.UTF_8);
                } catch (IOException _) {
                    return "";
                }
            });
            if (!process.waitFor(COMMAND_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return failed(session, "the command did not finish in %d seconds".formatted(COMMAND_SECONDS));
            }
            String text = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                return failed(session, "the command exited with code %d: %s".formatted(process.exitValue(), firstLine(text)));
            }
            Matcher link = LINK.matcher(text);
            String found = link.find() ? clean(link.group()) : null;
            sessions.setPullRequest(session.id(), "created", found);
            event(session, found == null ? "pull request command ran" : "pull request created: " + found);
            return new Outcome(PUSHED, "created", found, found == null ? "pull request command ran" : "pull request " + found);
        } catch (IOException e) {
            return failed(session, "the command could not be started: " + clean(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(session, "interrupted while the command ran");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return failed(session, "the command's output could not be read");
        }
    }

    private Outcome skipped(FixSession session, String message) {
        sessions.setPullRequest(session.id(), "skipped", null);
        event(session, message);
        return new Outcome(PUSHED, "skipped", null, message);
    }

    private Outcome failed(FixSession session, String message) {
        sessions.setPullRequest(session.id(), "failed", null);
        event(session, "pull request command failed (the branch stays pushed): " + clean(message));
        return new Outcome(PUSHED, "failed", null, "pull request command failed: " + clean(message));
    }

    /** The target branch: the session's source branch, else the repository's default branch. */
    private String baseOf(FixSession session, Path work) {
        if (session.sourceBranch() != null) {
            return session.sourceBranch();
        }
        try {
            DefaultBranch.Found found = DefaultBranch.detect(git, work);
            return found.branch() == null ? "HEAD" : found.branch();
        } catch (IOException _) {
            return "HEAD";
        }
    }

    private static String title(FixSession session) {
        return "Security fixes from automated session %s".formatted(session.id().substring(0, 8));
    }

    /** Engine-made text only: nothing from a report or an agent's output is part of it. */
    private static String description(FixSession session, String base) {
        StringBuilder text = new StringBuilder("""
                This branch was produced by an automated fix session of the appmod engine (session %s).
                It has %d commit%s on top of %s. Review every change before merging.
                """.formatted(session.id(), session.fixCommits(), session.fixCommits() == 1 ? "" : "s", base));
        if (session.localChanges()) {
            text.append("""

                    WARNING: this session started from a snapshot of uncommitted local work, so the branch also contains
                    changes that were never committed by their author (possibly including files that were not tracked).
                    """);
        }
        return text.toString();
    }

    private void event(FixSession session, String detail) {
        sessions.appendEvent(session.id(), clock.instant(), session.state(), clean(detail));
        log.info("Session {}: {}", session.id(), clean(detail));
    }

    private static String reasonOf(GitRunner.Result result) {
        String line = result.output().lines().map(String::strip).filter(l -> l.startsWith("fatal:") || l.startsWith("error:") || l.startsWith("remote:"))
                .findFirst().orElse(firstLine(result.output()));
        return clean(line.isEmpty() ? "git exited with code " + result.exitCode() : line);
    }

    private static String firstLine(String text) {
        String stripped = text == null ? "" : text.strip();
        int end = stripped.indexOf('\n');
        return clean(end < 0 ? stripped : stripped.substring(0, end));
    }

    /** Text from git or a command is shown and stored: credentials in a URL and control characters are removed first. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String noSecrets = USER_INFO.matcher(text).replaceAll("$1***@");
        return noSecrets.replaceAll("\\p{Cntrl}", " ").strip();
    }
}
