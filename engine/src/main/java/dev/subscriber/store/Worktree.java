package dev.subscriber.store;

/**
 * The git worktree a session works in, as recorded with the session.
 *
 * @param name       the worktree's name, which is also its folder name
 * @param path       the worktree's folder
 * @param branch     the branch the agent commits on
 * @param baseCommit the commit the worktree started from
 */
public record Worktree(String name, String path, String branch, String baseCommit) {

    /** For a session that works in the repository's own checkout. */
    public static final Worktree NONE = new Worktree(null, null, null, null);
}
