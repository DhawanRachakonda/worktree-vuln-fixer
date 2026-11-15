package dev.subscriber.worktree;

import dev.subscriber.worktree.WorktreeProvisioner.WorktreeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Decides the commit a session's worktree starts from, in one place: the source branch given at publish, the repository's saved
 * source branch, the commit the report names, the repository's default branch, then {@code HEAD}. A given or saved branch that cannot
 * be used is an error and never falls through to a lower choice. With local changes requested, the base is a snapshot commit of the
 * checkout's tracked and untracked changes, made with a temporary index so the checkout itself is not touched.
 */
final class SourceBranchResolver {

    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** The agent's own folder is never part of a snapshot. */
    private static final String EXCLUDE_APPMOD = ":(exclude).appmod";

    /** The decision: the commit, the branch it is the tip of (null when it is not a branch tip), which choice applied, and notes for the events. */
    record Decision(String commit, String branch, String choice, boolean localChanges, List<String> notes) {
    }

    private final GitRunner git;

    SourceBranchResolver(GitRunner git) {
        this.git = git;
    }

    Decision resolve(Path repository, Path report, String requestedBranch, boolean localChanges) throws IOException, WorktreeException {
        List<String> notes = new ArrayList<>();
        if (localChanges && requestedBranch != null) {
            throw new WorktreeException("a source branch and local changes cannot be combined: local changes belong to the checked-out branch");
        }
        RepositorySettings.Read saved = RepositorySettings.read(repository);
        if (!saved.ok()) {
            throw new WorktreeException(saved.problem());
        }
        if (localChanges) {
            return snapshot(repository, report, saved.settings(), notes);
        }
        ReportCommit.Result named = report == null ? ReportCommit.Result.none("the report is not a CSV file") : ReportCommit.read(report);
        if (requestedBranch != null) {
            return fromBranch(repository, requestedBranch, "publish", "the source branch given when the report was published", named, notes);
        }
        if (saved.settings().sourceBranch() != null) {
            return fromBranch(repository, saved.settings().sourceBranch(), "saved", "the repository's saved source branch", named, notes);
        }
        if (named.usable()) {
            GitRunner.Result found = git.run(repository, "rev-parse", "--verify", "--quiet", "%s^{commit}".formatted(named.commit()));
            String full = found.output().strip();
            if (found.ok() && FULL_COMMIT.matcher(full).matches()) {
                if (named.dirty()) {
                    notes.add("the audit ran on a working tree with uncommitted changes, so line numbers may differ from commit %s"
                            .formatted(full.substring(0, 7)));
                }
                return new Decision(full, null, "report-commit", false, notes);
            }
            notes.add("report commit not used: the commit %s is not in the repository".formatted(named.commit()));
        } else {
            notes.add("report commit not used: %s".formatted(named.problem()));
        }
        DefaultBranch.Found found = DefaultBranch.detect(git, repository);
        if (found.branch() != null) {
            String tip = tip(repository, found.branch());
            if (tip != null) {
                notes.add("started from the default branch %s (%s)".formatted(found.branch(), found.how().label()));
                return new Decision(tip, found.branch(), "default-branch", false, notes);
            }
            notes.add("the default branch %s (%s) has no commit here".formatted(found.branch(), found.how().label()));
        }
        String head = head(repository);
        notes.add("started from HEAD");
        return new Decision(head, DefaultBranch.currentBranch(git, repository), "head", false, notes);
    }

    private Decision fromBranch(Path repository, String branch, String choice, String what, ReportCommit.Result named, List<String> notes)
            throws IOException, WorktreeException {
        String why = BranchNames.problem(branch);
        if (why != null) {
            throw new WorktreeException("%s is not usable: %s".formatted(what, why));
        }
        String tip = tip(repository, branch);
        if (tip == null) {
            throw new WorktreeException("%s, %s, does not exist in the repository, neither as a local branch nor as origin/%s"
                    .formatted(what, branch, branch));
        }
        if (named.usable() && !tip.startsWith(named.commit())) {
            notes.add("report commit not used: the worktree starts from the source branch %s (%s), not from commit %s"
                    .formatted(branch, what, named.commit()));
        }
        return new Decision(tip, branch, choice, false, notes);
    }

    /** The tip of a local branch, else of its remote-tracking branch on origin; null when neither exists. */
    String tip(Path repository, String branch) throws IOException {
        for (String ref : new String[] {"refs/heads/" + branch, "refs/remotes/origin/" + branch}) {
            GitRunner.Result result = git.run(repository, "rev-parse", "--verify", "--quiet", "--end-of-options", ref + "^{commit}");
            String full = result.output().strip();
            if (result.ok() && FULL_COMMIT.matcher(full).matches()) {
                return full;
            }
        }
        return null;
    }

    private String head(Path repository) throws IOException, WorktreeException {
        GitRunner.Result head = git.run(repository, "rev-parse", "--verify", "--quiet", "HEAD^{commit}");
        String full = head.output().strip();
        if (!head.ok() || !FULL_COMMIT.matcher(full).matches()) {
            throw new WorktreeException("the repository has no commit to start a worktree from");
        }
        return full;
    }

    private Decision snapshot(Path repository, Path report, RepositorySettings saved, List<String> notes) throws IOException, WorktreeException {
        String tip = head(repository);
        String branch = DefaultBranch.currentBranch(git, repository);
        ReportCommit.Result named = report == null ? ReportCommit.Result.none("the report is not a CSV file") : ReportCommit.read(report);
        if (saved.sourceBranch() != null) {
            notes.add("the saved source branch %s was not used: local changes belong to the checked-out branch".formatted(saved.sourceBranch()));
        }
        if (named.usable()) {
            notes.add("report commit not used: the worktree starts from the checked-out branch with its local changes");
        }
        GitRunner.Result status = git.run(repository, "status", "--porcelain", "--untracked-files=all", "--", ".", EXCLUDE_APPMOD);
        if (!status.ok()) {
            throw new WorktreeException("git could not read the checkout's changes: " + status.reason());
        }
        if (status.output().isBlank()) {
            notes.add("nothing to include: the checkout has no uncommitted changes, so the worktree starts from the tip of %s"
                    .formatted(branch == null ? "HEAD" : branch));
            return new Decision(tip, branch, "local-changes", false, notes);
        }
        Path index = null;
        try {
            Path real = indexOf(repository);
            index = Files.createTempFile("appmod-index-", ".tmp");
            if (Files.isRegularFile(real)) {
                Files.copy(real, index, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.delete(index);
            }
            Map<String, String> env = Map.of("GIT_INDEX_FILE", index.toString(), "GIT_AUTHOR_NAME", "appmod engine",
                    "GIT_AUTHOR_EMAIL", "appmod@localhost", "GIT_COMMITTER_NAME", "appmod engine", "GIT_COMMITTER_EMAIL", "appmod@localhost");
            // no pathspec for .appmod: git refuses a pathspec that names an ignored path (appmod init ignores .appmod), so the agent's folder is dropped from the temporary index afterwards
            GitRunner.Result added = git.run(repository, env, "add", "-A");
            if (added.ok()) {
                added = git.run(repository, env, "rm", "-r", "--cached", "-q", "--ignore-unmatch", "--", ".appmod");
            }
            if (!added.ok()) {
                throw new WorktreeException("git could not collect the local changes: " + added.reason());
            }
            GitRunner.Result tree = git.run(repository, env, "write-tree");
            String treeId = tree.output().strip();
            if (!tree.ok() || !FULL_COMMIT.matcher(treeId).matches()) {
                throw new WorktreeException("git could not store the local changes: " + tree.reason());
            }
            GitRunner.Result commit = git.run(repository, env, "-c", "commit.gpgsign=false", "commit-tree", treeId, "-p", tip, "-m",
                    "Uncommitted local work of %s, taken by the appmod engine for a fix session (not a commit the author made)"
                            .formatted(branch == null ? "HEAD" : branch));
            String commitId = commit.output().strip();
            if (!commit.ok() || !FULL_COMMIT.matcher(commitId).matches()) {
                throw new WorktreeException("git could not record the local changes: " + commit.reason());
            }
            notes.add("local changes included: the worktree starts from a snapshot of %s with its uncommitted and untracked changes"
                    .formatted(branch == null ? "HEAD" : branch));
            return new Decision(commitId, branch, "local-changes", true, notes);
        } finally {
            if (index != null) {
                Files.deleteIfExists(index);
            }
        }
    }

    private Path indexOf(Path repository) throws IOException {
        GitRunner.Result where = git.run(repository, "rev-parse", "--git-path", "index");
        Path path = Path.of(where.firstLine());
        return path.isAbsolute() ? path : repository.resolve(path);
    }
}
