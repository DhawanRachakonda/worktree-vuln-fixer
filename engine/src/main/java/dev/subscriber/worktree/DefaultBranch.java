package dev.subscriber.worktree;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Finds a repository's default branch without asking the person and without fetching: the branch {@code origin/HEAD} points to,
 * then the branch the remote reports for its {@code HEAD}, then a local {@code main} or {@code master}, then the checked-out branch.
 * The answer says how it was found.
 */
public final class DefaultBranch {

    /** How the answer was found; the label is what the person is shown. */
    public enum How {
        REMOTE_DEFAULT("remote default"),
        LOCAL_GUESS("local guess"),
        NO_REMOTE("no remote");

        private final String label;

        How(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The default branch and how it was found; {@code branch} is null when the repository has none (detached HEAD, no main/master). */
    public record Found(String branch, How how) {
    }

    private DefaultBranch() {
    }

    static Found detect(GitRunner git, Path repository) throws IOException {
        boolean hasOrigin = git.run(repository, "remote", "get-url", "origin").ok();
        GitRunner.Result symbolic = git.run(repository, "symbolic-ref", "--short", "refs/remotes/origin/HEAD");
        if (symbolic.ok() && symbolic.firstLine().startsWith("origin/")) {
            String name = symbolic.firstLine().substring("origin/".length());
            if (BranchNames.valid(name)) {
                return new Found(name, How.REMOTE_DEFAULT);
            }
        }
        if (hasOrigin) {
            // the same call the reachability check makes; an unreachable remote only means this step finds nothing
            try {
                GitRunner.Result asked = git.run(repository, "ls-remote", "--symref", "origin", "HEAD");
                if (asked.ok()) {
                    for (String line : asked.output().lines().toList()) {
                        if (line.startsWith("ref: refs/heads/")) {
                            String name = line.substring("ref: refs/heads/".length()).split("\\s+")[0];
                            if (BranchNames.valid(name)) {
                                return new Found(name, How.REMOTE_DEFAULT);
                            }
                        }
                    }
                }
            } catch (IOException _) {
                // fall through to the local guesses
            }
        }
        for (String candidate : new String[] {"main", "master"}) {
            if (git.run(repository, "rev-parse", "--verify", "--quiet", "refs/heads/" + candidate).ok()) {
                return new Found(candidate, How.LOCAL_GUESS);
            }
        }
        String current = currentBranch(git, repository);
        return new Found(current, hasOrigin ? How.LOCAL_GUESS : How.NO_REMOTE);
    }

    /** The checked-out branch, or null on a detached HEAD. */
    static String currentBranch(GitRunner git, Path repository) throws IOException {
        GitRunner.Result head = git.run(repository, "symbolic-ref", "--quiet", "--short", "HEAD");
        return head.ok() && BranchNames.valid(head.firstLine()) ? head.firstLine() : null;
    }
}
