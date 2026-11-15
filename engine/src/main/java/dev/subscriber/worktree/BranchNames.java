package dev.subscriber.worktree;

import java.util.regex.Pattern;

/** What counts as a branch name when it comes from a message header, a saved setting or an option. Data, never a git option. */
public final class BranchNames {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._/-]+");
    private static final int MAX = 200;

    private BranchNames() {
    }

    /** Letters, digits and {@code ._/-}; no leading {@code -} or {@code /}, no {@code ..}, no {@code //}, no trailing {@code /}, {@code .} or {@code .lock}. */
    public static boolean valid(String name) {
        return problem(name) == null;
    }

    /** Why the name is not acceptable, or null when it is. */
    public static String problem(String name) {
        if (name == null || name.isEmpty()) {
            return "the branch name is empty";
        }
        if (name.length() > MAX) {
            return "the branch name is longer than %d characters".formatted(MAX);
        }
        if (!ALLOWED.matcher(name).matches()) {
            return "a branch name may only contain letters, digits and . _ / -";
        }
        if (name.startsWith("-") || name.startsWith("/") || name.contains("..") || name.contains("//") || name.endsWith("/")
                || name.endsWith(".") || name.endsWith(".lock") || name.contains("/.") || name.startsWith(".")) {
            return "that is not a valid git branch name";
        }
        return null;
    }
}
