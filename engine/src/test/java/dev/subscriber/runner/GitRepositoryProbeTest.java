package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.runner.RepositoryProbe.RepositoryState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepositoryProbeTest {

    @TempDir
    Path temp;

    private final GitRepositoryProbe probe = new GitRepositoryProbe();

    @Test
    void aCleanRepositoryGivesItsCommitAndNotDirty() throws Exception {
        Path repo = temp.resolve("clean");
        String head = StandInAgent.initRepository(repo);
        RepositoryState state = probe.probe(repo);
        assertThat(state.head()).isEqualTo(head);
        assertThat(state.dirty()).isFalse();
    }

    @Test
    void uncommittedChangesMakeItDirty() throws Exception {
        Path repo = temp.resolve("dirty");
        String head = StandInAgent.initRepository(repo);
        Files.writeString(repo.resolve("new-file.txt"), "x");
        RepositoryState state = probe.probe(repo);
        assertThat(state.head()).isEqualTo(head);
        assertThat(state.dirty()).isTrue();
    }

    @Test
    void aFolderThatIsNotARepositoryGivesNothing() throws Exception {
        Path plain = Files.createDirectories(temp.resolve("plain"));
        RepositoryState state = probe.probe(plain);
        assertThat(state.head()).isNull();
        assertThat(state.dirty()).isNull();
    }

    @Test
    void aFolderThatDoesNotExistGivesNothing() {
        RepositoryState state = probe.probe(temp.resolve("missing"));
        assertThat(state.head()).isNull();
        assertThat(state.dirty()).isNull();
    }

    @Test
    void aFolderNameWithShellCharactersIsPassedAsOneArgument() throws Exception {
        Path repo = temp.resolve("r&d 100% ^'x (1) é; calc");
        String head = StandInAgent.initRepository(repo);
        assertThat(probe.probe(repo).head()).isEqualTo(head);
    }
}
