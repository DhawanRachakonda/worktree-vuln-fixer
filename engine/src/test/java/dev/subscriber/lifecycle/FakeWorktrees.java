package dev.subscriber.lifecycle;

import dev.subscriber.store.Worktree;
import dev.subscriber.worktree.WorktreeProvisioner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Makes plain folders instead of git worktrees; can be told to fail for chosen sessions and to report a number of commits. */
class FakeWorktrees implements WorktreeProvisioner {

    static final String BASE = "a".repeat(40);

    private final Path folder;
    final List<String> created = new CopyOnWriteArrayList<>();
    final Set<String> failing = ConcurrentHashMap.newKeySet();
    /** Commit counts by the first 8 characters of the session id. */
    final Map<String, Integer> commits = new ConcurrentHashMap<>();
    final Map<String, List<String>> notes = new ConcurrentHashMap<>();

    FakeWorktrees(Path folder) {
        this.folder = folder;
    }

    @Override
    public Created create(String sessionId, Path repository, Path report) throws WorktreeException {
        if (failing.contains(sessionId)) {
            throw new WorktreeException("simulated: the folder is outside the allowed roots\u001b[31m");
        }
        String name = repository.getFileName() + "-fix-" + sessionId.substring(0, 8);
        Path work = folder.resolve(name);
        try {
            Files.createDirectories(work);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        created.add(sessionId);
        return new Created(new Worktree(name, work.toString(), "appmod/fix-" + sessionId.substring(0, 8), BASE),
                notes.getOrDefault(sessionId, List.of()));
    }

    /** Worktrees removed, by folder; and a reason that makes every removal refuse when set. */
    public final List<String> removed = new CopyOnWriteArrayList<>();
    public volatile String refuseRemoval;

    @Override
    public void remove(Path repository, String workPath, String branch) throws WorktreeException {
        if (refuseRemoval != null) {
            throw new WorktreeException(refuseRemoval);
        }
        try {
            Files.deleteIfExists(Path.of(workPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        removed.add(workPath);
    }

    @Override
    public Integer commitCount(String workPath, String baseCommit, String branch) {
        return commits.get(branch.substring("appmod/fix-".length()));
    }
}
