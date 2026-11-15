package dev.subscriber.worktree;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRunnerTest {

    @TempDir
    Path temp;

    @Test
    void extraEnvironmentReachesGit() throws Exception {
        Path repo = WorktreeServiceTest.initRepository(temp.toRealPath().resolve("r"));
        Path index = temp.resolve("other-index");
        GitRunner runner = new GitRunner(temp.resolve("no-hooks"), 60);
        // an empty index file of our own: git ls-files must see nothing, while the repository's real index lists a.txt
        assertThat(runner.run(repo, "ls-files").output().strip()).isEqualTo("a.txt");
        GitRunner.Result result = runner.run(repo, Map.of("GIT_INDEX_FILE", index.toString()), "ls-files");
        assertThat(result.ok()).isTrue();
        assertThat(result.output().strip()).isEmpty();
        assertThat(Files.exists(index)).as("the index file git was pointed at").isFalse();
    }

    @Test
    void gitDirAndWorkTreeFromTheEngineEnvironmentAreStillRemoved() throws Exception {
        Path repo = WorktreeServiceTest.initRepository(temp.toRealPath().resolve("r"));
        GitRunner runner = new GitRunner(temp.resolve("no-hooks"), 60);
        // the caller's map may add variables but cannot be what removes the guard; the working directory still decides the repository
        GitRunner.Result result = runner.run(repo, Map.of("EXTRA", "1"), "rev-parse", "--show-toplevel");
        assertThat(result.ok()).isTrue();
        assertThat(Path.of(result.firstLine()).toRealPath()).isEqualTo(repo);
    }
}
