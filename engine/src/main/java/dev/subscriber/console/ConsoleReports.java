package dev.subscriber.console;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The text of each console command. Pure functions of the records, so the output can be checked without a terminal. */
final class ConsoleReports {

    private static final String LIST_ROW = "%-38s%-13s%-7s%-21s%s";

    private ConsoleReports() {
    }

    static String sessions(List<FixSession> sessions, long total, SessionState filter) {
        return sessions(sessions, total, filter, s -> null);
    }

    /** As above; {@code waitsFor} gives the id of the session an item waits behind (same repository), or null. */
    static String sessions(List<FixSession> sessions, long total, SessionState filter,
            java.util.function.Function<FixSession, String> waitsFor) {
        String what = filter == null ? "sessions" : "%s sessions".formatted(filter);
        if (sessions.isEmpty()) {
            return "No %s.".formatted(what);
        }
        Stream<String> rows = sessions.stream().map(s -> LIST_ROW.formatted(s.id(), s.state().name(), fixes(s),
                s.receivedAt() == null ? "-" : s.receivedAt().truncatedTo(ChronoUnit.SECONDS).toString(),
                "%s / %s%s".formatted(Text.printable(s.source(), 24), Text.printable(s.repositoryPath(), 60), waiting(s, waitsFor.apply(s)))));
        return Stream.of(Stream.of(LIST_ROW.formatted("ID", "STATE", "FIXES", "RECEIVED", "SOURCE / REPOSITORY")), rows,
                Stream.of("Showing %d of %d %s, newest first.".formatted(sessions.size(), total, what)))
                .flatMap(lines -> lines).collect(Collectors.joining("\n"));
    }

    static String session(FixSession s) {
        return session(s, null);
    }

    /** As above; {@code blocker} is the earlier session of the same repository this one waits behind, or null. */
    static String session(FixSession s, FixSession blocker) {
        List<String> lines = new ArrayList<>(List.of(
                line("id", s.id()),
                line("state", s.state().name()),
                line("subject", s.subject()),
                line("source", s.source()),
                line("report", s.report()),
                line("repository", s.repositoryPath()),
                line("agent", s.agent()),
                line("runner", s.runner()),
                line("received", s.receivedAt()),
                line("started", s.startedAt()),
                line("ended", s.endedAt()),
                line("exit code", s.exitCode()),
                line("head at start", s.headStart()),
                line("head at end", s.headEnd()),
                line("dirty at start", s.dirtyAtStart()),
                line("outcome", s.outcome()),
                line("reason", s.reason())));
        if (blocker != null && s.state() == SessionState.QUEUED) {
            lines.add(2, line("waiting", "behind %s (%s): one session at a time runs in a repository".formatted(blocker.id(), blocker.state().name())));
        }
        if (s.sourceChoice() != null || s.sourceRequested() != null || s.localChanges()) {
            lines.add("");
            lines.add(line("source branch", s.sourceBranch() != null ? s.sourceBranch() : s.sourceRequested() != null ? s.sourceRequested() + " (requested)" : "-"));
            lines.add(line("source chosen by", s.sourceChoice() == null ? "not yet decided" : s.sourceChoice()));
            lines.add(line("local changes", s.localChanges() ? "included (snapshot of uncommitted work)" : "no"));
        }
        if (s.publishState() != null && !"none".equals(s.publishState()) || s.pullRequestState() != null) {
            lines.add(line("publish", s.publishState() == null ? "none" : s.publishState()));
            lines.add(line("pull request", s.pullRequestState() == null ? "none" : s.pullRequestState()));
            lines.add(line("pull request link", s.pullRequestLink()));
        }
        if ("awaiting_confirmation".equals(s.publishState()) || "push_failed".equals(s.publishState())) {
            lines.add("To publish:  publish-branch %s --yes   (without --yes it only says what it would do)".formatted(s.id()));
        }
        if (s.hasWorktree()) {
            lines.add("");
            lines.addAll(List.of(
                    line("worktree", s.workName()),
                    line("worktree folder", s.workPath() + (folderExists(s.workPath()) ? "" : "  (no longer exists)")),
                    line("branch", s.workBranch()),
                    line("base commit", s.baseCommit()),
                    line("commits made", s.fixCommits() == null ? "not counted" : s.fixCommits())));
            lines.addAll(worktreeCommands(s));
        }
        return String.join("\n", lines).stripTrailing();
    }

    private static String waiting(FixSession s, String behind) {
        return behind == null || s.state() != SessionState.QUEUED ? "" : "  (waits behind %s)".formatted(behind.length() > 8 ? behind.substring(0, 8) : behind);
    }

    /** The commits the agent made on the session's branch when there are any, else a dash; the marker the list shows. */
    static String fixes(FixSession s) {
        return s.hasWorktree() && s.fixCommits() != null && s.fixCommits() > 0 ? String.valueOf(s.fixCommits()) : "-";
    }

    /**
     * The git commands to look at the fixes and to remove the worktree. They are made from values the engine recorded and shown
     * only; the engine never runs them. A value with a quote in it is not put inside quotes, so a command is left out instead.
     */
    private static List<String> worktreeCommands(FixSession s) {
        String repository = s.repositoryPath();
        String range = "%s..%s".formatted(s.baseCommit(), s.workBranch());
        if (repository == null || repository.contains("\"") || s.workPath().contains("\"") || range.contains("\"")) {
            return List.of("(the git commands are not shown: a recorded path or name contains a quote)");
        }
        String git = "git -C \"%s\" ".formatted(Text.printable(repository));
        String folder = Text.printable(s.workPath());
        List<String> lines = new ArrayList<>(List.of(
                "",
                "To review:   %slog %s".formatted(git, Text.printable(range)),
                "             %sdiff %s".formatted(git, Text.printable(range))));
        if (folderExists(s.workPath())) {
            // The engine's own command checks for uncommitted work first and leaves the branch alone.
            lines.add("             git -C \"%s\" status --short   (anything listed is not committed; cleanup refuses while there is any)".formatted(folder));
            lines.add("To remove the folder:  cleanup %s   (add --yes to do it; the branch and its commits stay)".formatted(s.id()));
        }
        lines.add("To remove the branch:  %sbranch -D %s   (only after you have merged or no longer need the commits)"
                .formatted(git, Text.printable(s.workBranch())));
        return lines;
    }

    private static boolean folderExists(String folder) {
        try {
            return folder != null && Files.isDirectory(Path.of(folder));
        } catch (InvalidPathException _) {
            return false;
        }
    }

    static String events(List<SessionEvent> events) {
        if (events.isEmpty()) {
            return "No events.";
        }
        return events.stream().map(e -> "%-28s%-13s%s".formatted(e.occurredAt(), e.state().name(), Text.printable(e.detail(), 200)))
                .collect(Collectors.joining("\n"));
    }

    /** The settings in force. Only whether a secret is set is shown, never the secret. */
    static String config(SubscriberProperties p, String bindAddress) {
        String authentication = p.nats().token() != null ? "token" : p.nats().user() != null ? "user and password"
                : p.nats().credentialsFile() != null ? "credentials file" : "none";
        List<String> lines = new ArrayList<>(List.of(
                line("data dir", p.dataDir()),
                line("allowed roots", String.join(", ", p.allowedRoots())),
                line("runner", p.effectiveRunner().name().toLowerCase()),
                line("max parallel", p.maxParallel()),
                line("agent tool", p.agentTool().name().toLowerCase()),
                line(p.agentTool().name().toLowerCase() + " command", p.agentCommand()),
                line("permission mode", p.copilot().permissionMode().name().toLowerCase().replace('_', '-')),
                line("nats url", p.nats().url()),
                line("nats authentication", authentication),
                line("api bind address", bindAddress == null || bindAddress.isBlank() ? "(all interfaces)" : bindAddress),
                line("api access token", p.access().token() == null ? "not set" : "set")));
        if (p.copilot().permissionMode() == SubscriberProperties.PermissionMode.ALLOW_LIST) {
            p.copilot().allowedTools().forEach(rule -> lines.add(line("allowed tool", rule)));
            p.copilot().deniedTools().forEach(rule -> lines.add(line("denied tool", rule)));
        }
        lines.add(line("publish mode", p.publish().mode().name().toLowerCase().replace('_', '-')));
        lines.add(line("pull-request command", p.publish().pullRequestCommand() == null ? "not set" : p.publish().pullRequestCommand()));
        lines.add(line("source branch", "per repository in .appmod/publish.json (saved by appmod init), else given with publish --source-branch, else the repository's default branch"));
        lines.add(line("local changes", "not included unless publish --unsafe-local-changes is given"));
        p.subjects().stream().map(SubscriberProperties.Subject::repository).filter(java.util.Objects::nonNull).distinct().forEach(repository -> {
            dev.subscriber.worktree.RepositorySettings.Read saved = dev.subscriber.worktree.RepositorySettings.read(Path.of(repository));
            lines.add(line("repository settings", "%s: %s".formatted(repository, !saved.ok() ? "INVALID: " + saved.problem()
                    : saved.settings().equals(dev.subscriber.worktree.RepositorySettings.EMPTY) ? "none saved"
                    : "source branch %s, mode %s, command %s".formatted(saved.settings().sourceBranch() == null ? "(default)" : saved.settings().sourceBranch(),
                            saved.settings().mode() == null ? "(engine's)" : saved.settings().mode().name().toLowerCase().replace('_', '-'),
                            saved.settings().pullRequestCommand() == null ? "(engine's)" : saved.settings().pullRequestCommand()))));
        });
        p.subjects().forEach(s -> lines.add(line("subject", "%s -> %s, %s".formatted(s.subject(), s.agent(),
                s.repository() != null ? "repository %s".formatted(s.repository()) : "repository from report"))));
        return String.join("\n", lines).stripTrailing();
    }

    private static String line(String label, Object value) {
        return "%-22s%s".formatted(label, value == null ? "-" : Text.printable(String.valueOf(value)));
    }
}
