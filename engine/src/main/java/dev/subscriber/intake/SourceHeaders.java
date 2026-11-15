package dev.subscriber.intake;

import dev.subscriber.worktree.BranchNames;
import java.util.List;

/** Reads the two optional message headers. They are data from outside: a value is accepted only in its exact form. */
final class SourceHeaders {

    static final String SOURCE_BRANCH = "source-branch";
    static final String LOCAL_CHANGES = "unsafe-local-changes";

    /** The accepted values, or the reason the headers were refused. */
    record Parsed(String branch, boolean localChanges, String problem) {
    }

    private SourceHeaders() {
    }

    static Parsed parse(List<String> branchValues, List<String> localValues) {
        List<String> branches = branchValues == null ? List.of() : branchValues;
        List<String> locals = localValues == null ? List.of() : localValues;
        if (branches.size() > 1) {
            return refused("the header %s was given more than once".formatted(SOURCE_BRANCH));
        }
        if (locals.size() > 1) {
            return refused("the header %s was given more than once".formatted(LOCAL_CHANGES));
        }
        String branch = branches.isEmpty() ? null : branches.getFirst();
        if (branch != null) {
            String why = BranchNames.problem(branch);
            if (why != null) {
                return refused("invalid header %s: %s".formatted(SOURCE_BRANCH, why));
            }
        }
        boolean local = false;
        if (!locals.isEmpty()) {
            if (!"true".equals(locals.getFirst())) {
                return refused("invalid header %s: the only accepted value is true".formatted(LOCAL_CHANGES));
            }
            local = true;
        }
        if (branch != null && local) {
            return refused("the headers %s and %s cannot be combined: local changes belong to the checked-out branch"
                    .formatted(SOURCE_BRANCH, LOCAL_CHANGES));
        }
        return new Parsed(branch, local, null);
    }

    private static Parsed refused(String problem) {
        return new Parsed(null, false, problem);
    }
}
