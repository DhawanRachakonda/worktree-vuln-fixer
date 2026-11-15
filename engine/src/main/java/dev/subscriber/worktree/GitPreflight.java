package dev.subscriber.worktree;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.PublishMode;
import dev.subscriber.console.CommandFinder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Finds out, before any agent has worked, whether git can do what the chosen publish mode needs: git installed, a remote named
 * {@code origin}, the host reachable with the credentials git already has, a push likely to be accepted ({@code push --dry-run},
 * reported as "likely" and never as proven), the source branch present, and the pull-request command's program on the PATH. Every
 * result says what it means for the person. It never fetches and never pushes.
 *
 * <p>A copy of these checks lives in the kit's installer ({@code kit/install}) because the installer cannot use engine classes;
 * AGENTS.md lists the two as deliberate copies that a parity test compares.
 */
@Component
public class GitPreflight {

    public enum Status { OK, WARN, FAIL }

    /** One check: its name, how it went, the detail, and what it means for the person ({@code consequence} is empty when OK). */
    public record Check(String check, Status status, String detail, String consequence) {

        public String line() {
            String mark = switch (status) {
                case OK -> "ok";
                case WARN -> "warning";
                case FAIL -> "FAILED";
            };
            return consequence.isEmpty() ? "[%s] %s: %s".formatted(mark, check, detail)
                    : "[%s] %s: %s\n        Consequence: %s".formatted(mark, check, detail, consequence);
        }
    }

    private final GitRunner git;
    private final CommandFinder finder;
    private final SubscriberProperties properties;

    @Autowired
    public GitPreflight(SubscriberProperties properties) {
        this(properties, new GitRunner(WorktreeService.emptyHooksFolder(properties), 20), null);
    }

    GitPreflight(SubscriberProperties properties, GitRunner git, CommandFinder finder) {
        this.properties = properties;
        this.git = git;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        this.finder = finder != null ? finder : new CommandFinder(System.getenv("PATH"), windows, System.getenv("PATHEXT"));
    }

    /**
     * All checks for one repository, including the ones that use the network.
     *
     * @param requestedBranch a source branch to check as well (from a publish), or null to check the saved or default one
     */
    public List<Check> run(Path repository, String requestedBranch) {
        List<Check> checks = new ArrayList<>();
        Check installed = gitInstalled(repository);
        checks.add(installed);
        if (installed.status() == Status.FAIL) {
            return checks;
        }
        RepositorySettings.Read saved = RepositorySettings.read(repository);
        PublishMode mode = effectiveMode(saved);
        checks.add(settings(saved));
        if (mode == PublishMode.NONE) {
            // Nothing is pushed in this mode, so the remote is not looked at (the installer is the one place that still warns).
            checks.add(new Check("remote checks", Status.OK, "skipped: the publish mode is none, so nothing is pushed", ""));
        } else {
            try {
                GitRunner.Result url = git.run(repository, "remote", "get-url", "origin");
                checks.add(originCheck(url, mode));
                if (url.ok()) {
                    checks.add(reachable(repository, mode));
                    checks.add(pushLikely(repository, mode));
                }
            } catch (IOException e) {
                checks.add(new Check("remote origin", Status.FAIL, BranchPublisher.clean(e.getMessage()), "git could not be run for this repository"));
            }
        }
        checks.add(sourceBranch(repository, saved, requestedBranch));
        if (mode == PublishMode.PULL_REQUEST) {
            checks.add(pullRequestProgram(saved));
        }
        return checks;
    }

    /** The checks that need no network, for publish time: git works, the saved setting is valid, the source branch exists. */
    public List<Check> cheap(Path repository, String requestedBranch) {
        List<Check> checks = new ArrayList<>();
        Check installed = gitInstalled(repository);
        checks.add(installed);
        if (installed.status() == Status.FAIL) {
            return checks;
        }
        RepositorySettings.Read saved = RepositorySettings.read(repository);
        checks.add(settings(saved));
        checks.add(sourceBranch(repository, saved, requestedBranch));
        return checks;
    }

    public static boolean failed(List<Check> checks) {
        return checks.stream().anyMatch(c -> c.status() == Status.FAIL);
    }

    private PublishMode effectiveMode(RepositorySettings.Read saved) {
        if (saved.ok() && saved.settings().mode() != null) {
            return saved.settings().mode();
        }
        return properties.publish().mode();
    }

    private Check gitInstalled(Path repository) {
        try {
            GitRunner.Result version = git.run(repository, "--version");
            return version.ok() ? new Check("git", Status.OK, version.firstLine(), "")
                    : new Check("git", Status.FAIL, "git did not answer", "no session can create a worktree");
        } catch (IOException e) {
            return new Check("git", Status.FAIL, "git was not found on the PATH", "no session can create a worktree or publish a branch");
        }
    }

    private Check settings(RepositorySettings.Read saved) {
        if (!saved.ok()) {
            return new Check("saved settings", Status.FAIL, saved.problem(),
                    "sessions for this repository end as ERROR until the file is fixed or removed");
        }
        RepositorySettings s = saved.settings();
        if (s.equals(RepositorySettings.EMPTY)) {
            return new Check("saved settings", Status.OK, "none saved (the engine's defaults apply)", "");
        }
        return new Check("saved settings", Status.OK, "source branch %s, mode %s".formatted(s.sourceBranch() == null ? "(not set)" : s.sourceBranch(),
                s.mode() == null ? "(engine's)" : s.mode().name().toLowerCase(Locale.ROOT).replace('_', '-')), "");
    }

    private Check originCheck(GitRunner.Result url, PublishMode mode) {
        if (url.ok()) {
            return new Check("remote origin", Status.OK, BranchPublisher.clean(url.firstLine()), "");
        }
        return new Check("remote origin", severity(mode), "this repository has no remote named origin",
                mode == PublishMode.NONE ? "fine while the publish mode is none; push and pull-request modes need it"
                        : "the branch of a finished session cannot be pushed; it stays a local branch");
    }

    private Check reachable(Path repository, PublishMode mode) {
        try {
            GitRunner.Result listed = git.run(repository, Map.of("GCM_INTERACTIVE", "never"), "ls-remote", "--heads", "origin");
            if (listed.ok()) {
                return new Check("remote reachable", Status.OK, "origin answered with the credentials git has", "");
            }
            return new Check("remote reachable", severity(mode), reason(listed),
                    mode == PublishMode.NONE ? "matters when the publish mode is push or pull-request"
                            : "the branch cannot be pushed until the host is reachable and git has credentials for it");
        } catch (IOException e) {
            return new Check("remote reachable", severity(mode), BranchPublisher.clean(e.getMessage()),
                    mode == PublishMode.NONE ? "matters when the publish mode is push or pull-request"
                            : "the branch cannot be pushed until the host answers in time");
        }
    }

    private Check pushLikely(Path repository, PublishMode mode) {
        try {
            GitRunner.Result dry = git.run(repository, Map.of("GCM_INTERACTIVE", "never"), "push", "--dry-run", "origin",
                    "HEAD:refs/heads/appmod/preflight-probe");
            if (dry.ok()) {
                return new Check("push allowed", Status.OK, "likely: a dry run was accepted (nothing was sent; a read-only credential can still fail later)", "");
            }
            return new Check("push allowed", severity(mode), reason(dry),
                    mode == PublishMode.NONE ? "matters when the publish mode is push or pull-request"
                            : "the push of a session's branch is likely to be refused");
        } catch (IOException e) {
            return new Check("push allowed", severity(mode), BranchPublisher.clean(e.getMessage()), "the push could not be checked in time");
        }
    }

    private Check sourceBranch(Path repository, RepositorySettings.Read saved, String requested) {
        String branch = requested != null ? requested : saved.ok() ? saved.settings().sourceBranch() : null;
        String how = requested != null ? "given" : "saved";
        try {
            if (branch == null) {
                DefaultBranch.Found found = DefaultBranch.detect(git, repository);
                branch = found.branch();
                how = found.how().label();
                if (branch == null) {
                    return new Check("source branch", Status.WARN, "no default branch could be found (detached HEAD)",
                            "sessions start from HEAD of the checkout");
                }
            }
            String problem = BranchNames.problem(branch);
            if (problem != null) {
                return new Check("source branch", Status.FAIL, "%s is not usable: %s".formatted(branch, problem), "sessions for it end as ERROR");
            }
            String tip = new SourceBranchResolver(git).tip(repository, branch);
            return tip == null
                    ? new Check("source branch", Status.FAIL, "%s (%s) does not exist locally or as origin/%s".formatted(branch, how, branch),
                            "a session for it ends as ERROR before any work is done")
                    : new Check("source branch", Status.OK, "%s (%s), tip %s".formatted(branch, how, tip.substring(0, 7)), "");
        } catch (IOException e) {
            return new Check("source branch", Status.FAIL, BranchPublisher.clean(e.getMessage()), "git could not be run");
        }
    }

    private Check pullRequestProgram(RepositorySettings.Read saved) {
        String template = saved.ok() && saved.settings().pullRequestCommand() != null ? saved.settings().pullRequestCommand()
                : properties.publish().pullRequestCommand();
        if (template == null) {
            return new Check("pull-request command", Status.FAIL, "mode is pull-request but no command is configured",
                    "the branch is pushed, but nobody opens the pull request; you do it by hand");
        }
        PullRequestCommand.Parsed parsed = PullRequestCommand.parse(template);
        if (!parsed.ok()) {
            return new Check("pull-request command", Status.FAIL, parsed.problem(), "the branch is pushed, but no pull request is opened");
        }
        Optional<Path> program = finder.find(parsed.program());
        return program.isPresent()
                ? new Check("pull-request command", Status.OK, "will run %s".formatted(program.get()), "")
                : new Check("pull-request command", Status.FAIL, "the program %s is not on the PATH".formatted(parsed.program()),
                        "the branch is pushed, but no pull request is opened");
    }

    private static Status severity(PublishMode mode) {
        return mode == PublishMode.NONE ? Status.WARN : Status.FAIL;
    }

    private static String reason(GitRunner.Result result) {
        String line = result.output().lines().map(String::strip).filter(l -> l.startsWith("fatal:") || l.startsWith("error:") || l.startsWith("remote:"))
                .findFirst().orElse(result.firstLine());
        return BranchPublisher.clean(line.isEmpty() ? "git exited with code " + result.exitCode() : line);
    }
}
