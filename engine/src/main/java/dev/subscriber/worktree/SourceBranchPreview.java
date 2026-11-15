package dev.subscriber.worktree;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.worktree.WorktreeProvisioner.WorktreeException;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Says, before a report is published, which source branch a session would start from and how that was found, using the same
 * resolver as the worktree and only read-only git. It refuses (with the reason) when the given or saved branch cannot be used, so the
 * person learns it at the console and not after a queued session fails.
 */
@Component
public class SourceBranchPreview {

    /** {@code ok} false means the publish must not go ahead; {@code message} is shown to the person either way. */
    public record Preview(boolean ok, String message) {
    }

    private final GitRunner git;

    @Autowired
    public SourceBranchPreview(SubscriberProperties properties) {
        this(new GitRunner(WorktreeService.emptyHooksFolder(properties), 30));
    }

    SourceBranchPreview(GitRunner git) {
        this.git = git;
    }

    public Preview describe(Path repository, Path report, String requestedBranch, boolean localChanges) {
        try {
            if (localChanges) {
                String branch = DefaultBranch.currentBranch(git, repository);
                return new Preview(true, ("Source: the checked-out branch %s with its uncommitted and untracked changes (a snapshot commit; "
                        + "the checkout is not touched). The session's branch will not be published without a second confirmation.")
                        .formatted(branch == null ? "(detached HEAD)" : branch));
            }
            SourceBranchResolver.Decision decision = new SourceBranchResolver(git).resolve(repository, report, requestedBranch, false);
            String how = switch (decision.choice()) {
                case "publish" -> "given";
                case "saved" -> "saved for this repository in .appmod/publish.json";
                case "report-commit" -> "the commit the report names";
                case "default-branch" -> DefaultBranch.detect(git, repository).how().label();
                default -> "HEAD of the checkout";
            };
            String what = decision.branch() != null ? "branch %s".formatted(decision.branch())
                    : "commit %s".formatted(decision.commit().substring(0, 7));
            return new Preview(true, "Source: %s (%s).".formatted(what, how));
        } catch (WorktreeException e) {
            return new Preview(false, "Nothing was published: " + e.getMessage());
        } catch (IOException e) {
            return new Preview(false, "Nothing was published: git could not be run: " + e.getMessage());
        }
    }
}
