package dev.subscriber.worktree;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.store.Worktree;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Gives a fix session its own copy of the repository: a git worktree on a new branch, next to the repository and inside an allowed
 * root. The person's checkout is not switched and its files are not changed; the only thing done to the repository is the metadata
 * git keeps for a worktree and one new branch.
 *
 * <p>Everything that reaches a git argument is made here from validated pieces: a 40-digit commit id, a branch of the fixed form
 * {@code appmod/fix-<8 hexadecimal digits>} and an absolute folder. The repository path is only the working directory.
 */
@Component
public class WorktreeService implements WorktreeProvisioner {

    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern FULL_COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern BRANCH = Pattern.compile("appmod/fix-[0-9a-f]{8}");
    private static final Pattern UNSAFE =Pattern.compile("[^A-Za-z0-9._-]");

    /** Windows refuses paths over 260 characters in many tools; the folder leaves room for the files inside it. */
    static final int MAX_WINDOWS_FOLDER = 200;

    /** Git also makes {@code <repository>/.git/worktrees/<name>} (with files in it) when it adds a worktree; the same 260 applies there. */
    static final int MAX_WINDOWS_ADMIN = 230;

    private final List<Path> allowedRoots;
    private final GitRunner git;
    private final boolean windows;

    @Autowired
    public WorktreeService(SubscriberProperties properties) {
        this(properties.allowedRoots().stream().map(WorktreeService::realRoot).toList(),
                new GitRunner(emptyHooksFolder(properties), 30));
    }

    WorktreeService(List<Path> allowedRealRoots, GitRunner git) {
        this(allowedRealRoots, git, System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"));
    }

    WorktreeService(List<Path> allowedRealRoots, GitRunner git, boolean windows) {
        this.allowedRoots = allowedRealRoots;
        this.git = git;
        this.windows = windows;
    }

    @Override
    public Created create(String sessionId, Path repository, Path report) throws WorktreeException {
        return create(sessionId, repository, report, SourceRequest.NONE);
    }

    @Override
    public Created create(String sessionId, Path repository, Path report, SourceRequest request) throws WorktreeException {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new WorktreeException("the session id is not a UUID");
        }
        String id8 = sessionId.substring(0, 8);
        String name = "%s-fix-%s".formatted(safeName(repository.getFileName() == null ? "repository" : repository.getFileName().toString()), id8);
        String branch = "appmod/fix-%s".formatted(id8);
        Path folder = locate(repository, name);
        int admin = repository.toString().length() + "/.git/worktrees/".length() + name.length();
        if (windows && admin > MAX_WINDOWS_ADMIN) {
            throw new WorktreeException(("git would keep the worktree's own files in a folder %d characters long inside the repository, over the "
                    + "limit of %d that leaves room for them on Windows; use a repository folder with a shorter path or name").formatted(admin, MAX_WINDOWS_ADMIN));
        }
        if (windows && folder.toString().length() > MAX_WINDOWS_FOLDER) {
            throw new WorktreeException(("the worktree folder would be %d characters long, over the limit of %d that leaves room for the files "
                    + "inside on Windows; use a repository folder with a shorter path or name").formatted(folder.toString().length(), MAX_WINDOWS_FOLDER));
        }

        try {
            if (Files.exists(folder)) {
                throw new WorktreeException("the worktree folder %s already exists".formatted(folder));
            }
            if (git.run(repository, "rev-parse", "--verify", "--quiet", "refs/heads/%s".formatted(branch)).ok()) {
                throw new WorktreeException("the branch %s already exists".formatted(branch));
            }
            List<String> notes = new ArrayList<>();
            SourceBranchResolver.Decision decision = new SourceBranchResolver(git).resolve(repository, report, request.branch(),
                    request.localChanges());
            String base = decision.commit();
            notes.addAll(decision.notes());
            GitRunner.Result added = git.run(repository, "worktree", "add", "-b", branch, folder.toString(), base);
            if (!added.ok()) {
                cleanUp(repository, folder, branch);
                throw new WorktreeException("git could not create the worktree: " + reasonOf(added));
            }
            log.info("Worktree {} created on {} from {}", name, branch, base);
            return new Created(new Worktree(name, folder.toString(), branch, base), List.copyOf(notes),
                    new Source(decision.branch(), decision.choice(), decision.localChanges()));
        } catch (IOException e) {
            cleanUp(repository, folder, branch);
            throw new WorktreeException("git could not be run: " + e.getMessage());
        }
    }

    @Override
    public Integer commitCount(String workPath, String baseCommit, String branch) {
        if (workPath == null || baseCommit == null || branch == null || !FULL_COMMIT.matcher(baseCommit).matches()
                || !branch.matches("appmod/fix-[0-9a-f]{8}")) {
            return null;
        }
        Path folder = Path.of(workPath);
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try {
            GitRunner.Result result = git.run(folder, "rev-list", "--count", "%s..%s".formatted(baseCommit, branch));
            return result.ok() ? Integer.valueOf(result.output().strip()) : null;
        } catch (IOException | NumberFormatException _) {
            return null;
        }
    }

    @Override
    public void remove(Path repository, String workPath, String branch) throws WorktreeException {
        if (workPath == null || branch == null || !BRANCH.matcher(branch).matches()) {
            throw new WorktreeException("the recorded branch is not one the engine made");
        }
        String id8 = branch.substring("appmod/fix-".length());
        Path folder;
        try {
            folder = Path.of(workPath).toRealPath();
        } catch (IOException | RuntimeException _) {
            throw new WorktreeException("the worktree folder is already gone");
        }
        if (folder.getFileName() == null || !folder.getFileName().toString().endsWith("-fix-" + id8)) {
            throw new WorktreeException("the folder %s does not have the name the engine gives a worktree".formatted(folder.getFileName()));
        }
        if (allowedRoots.stream().noneMatch(folder::startsWith)) {
            throw new WorktreeException("the folder %s is outside the allowed roots".formatted(folder));
        }
        try {
            if (!listedWorktrees(repository).contains(folder)) {
                throw new WorktreeException("git does not list %s as a worktree of %s".formatted(folder, repository));
            }
            GitRunner.Result status = git.run(folder, "status", "--porcelain", "--untracked-files=all");
            if (!status.ok()) {
                throw new WorktreeException("git could not read the worktree: " + reasonOf(status));
            }
            List<String> unsaved = status.output().lines().filter(line -> line.length() > 3)
                    .map(line -> line.substring(3).strip()).filter(path -> !path.startsWith(".appmod/") && !path.startsWith("\".appmod/")).toList();
            if (!unsaved.isEmpty()) {
                throw new WorktreeException("the worktree has uncommitted work (%d file%s, for example %s); commit it or remove it by hand"
                        .formatted(unsaved.size(), unsaved.size() == 1 ? "" : "s", printable(unsaved.getFirst())));
            }
            GitRunner.Result removed = git.run(repository, "worktree", "remove", "--force", folder.toString());
            if (!removed.ok() || Files.exists(folder)) {
                throw new WorktreeException("git could not remove the worktree: " + reasonOf(removed));
            }
            git.run(repository, "worktree", "prune");
            log.info("Worktree {} removed; branch {} kept", folder.getFileName(), branch);
        } catch (IOException e) {
            throw new WorktreeException("git could not be run: " + e.getMessage());
        }
    }

    /** The real paths of the linked worktrees git lists for the repository (the repository's own checkout is the first entry and is skipped). */
    private List<Path> listedWorktrees(Path repository) throws IOException, WorktreeException {
        GitRunner.Result listed = git.run(repository, "worktree", "list", "--porcelain");
        if (!listed.ok()) {
            throw new WorktreeException("git could not list the worktrees of %s: %s".formatted(repository, reasonOf(listed)));
        }
        List<Path> paths = new ArrayList<>();
        listed.output().lines().filter(line -> line.startsWith("worktree ")).skip(1).forEach(line -> {
            try {
                paths.add(Path.of(line.substring("worktree ".length()).strip()).toRealPath());
            } catch (IOException | RuntimeException _) {
                // a listed folder that no longer exists cannot be the one asked for
            }
        });
        return paths;
    }

    private static String printable(String text) {
        String cleaned = text.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) + "..." : cleaned;
    }

    private Path locate(Path repository, String name) throws WorktreeException {
        Path parent = repository.getParent();
        if (parent == null) {
            throw new WorktreeException("the repository has no parent folder to put a worktree in");
        }
        Path realParent;
        try {
            realParent = parent.toRealPath();
        } catch (IOException _) {
            throw new WorktreeException("the folder for the worktree cannot be resolved: " + parent);
        }
        if (allowedRoots.stream().noneMatch(realParent::startsWith)) {
            throw new WorktreeException(
                    "the worktree would be created in %s, which is outside the allowed roots; no worktree was created".formatted(realParent));
        }
        return realParent.resolve(name);
    }

    /** Removes what a failed attempt created: the folder (only if it did not exist before) and the branch (only if it is ours). */
    private void cleanUp(Path repository, Path folder, String branch) {
        try {
            if (Files.exists(folder)) {
                git.run(repository, "worktree", "remove", "--force", folder.toString());
                deleteTree(folder);
            }
            git.run(repository, "worktree", "prune");
            git.run(repository, "branch", "-D", branch);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not fully clean up after a failed worktree: {}", e.getMessage());
        }
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** git's own error line when there is one ("Preparing worktree ..." comes first and says nothing), else its first line. */
    private static String reasonOf(GitRunner.Result result) {
        String line = result.output().lines().map(String::strip).filter(l -> l.startsWith("fatal:") || l.startsWith("error:")).findFirst()
                .orElse(result.firstLine());
        return line.isEmpty() ? "exit code " + result.exitCode() : line.replaceAll("\\p{Cntrl}", "?");
    }

    static String safeName(String name) {
        String cleaned = UNSAFE.matcher(name).replaceAll("_");
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80);
        }
        return cleaned.isBlank() || cleaned.chars().allMatch(c -> c == '.') ? "repository" : cleaned;
    }

    private static Path realRoot(String root) {
        try {
            return Path.of(root).toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("subscriber.allowed-roots entry cannot be resolved: " + root, e);
        }
    }

    static Path emptyHooksFolder(SubscriberProperties properties) {
        try {
            return Files.createDirectories(Path.of(properties.dataDir()).toAbsolutePath().normalize().resolve("no-hooks"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the empty hooks folder", e);
        }
    }
}
