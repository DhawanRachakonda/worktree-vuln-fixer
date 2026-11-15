package dev.subscriber.worktree;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties.PublishMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositorySettingsTest {

    @TempDir
    Path temp;

    private Path repo(String name, String json) throws Exception {
        Path repo = Files.createDirectories(temp.resolve(name));
        if (json != null) {
            Files.createDirectories(repo.resolve(".appmod"));
            Files.writeString(repo.resolve(RepositorySettings.LOCATION), json);
        }
        return repo;
    }

    @Test
    void noFileMeansEveryValueIsTheEnginesDefault() throws Exception {
        RepositorySettings.Read read = RepositorySettings.read(repo("none", null));
        assertThat(read.ok()).isTrue();
        assertThat(read.settings()).isEqualTo(RepositorySettings.EMPTY);
    }

    @Test
    void aCompleteFileIsRead() throws Exception {
        RepositorySettings.Read read = RepositorySettings.read(repo("full", """
                {"sourceBranch": "release/1.2", "mode": "pull-request", "pullRequestCommand": "gh pr create --base {base}"}"""));
        assertThat(read.ok()).isTrue();
        assertThat(read.settings()).isEqualTo(new RepositorySettings("release/1.2", PublishMode.PULL_REQUEST, "gh pr create --base {base}"));
    }

    @Test
    void twoRepositoriesKeepTheirOwnSettings() throws Exception {
        RepositorySettings a = RepositorySettings.read(repo("a", "{\"sourceBranch\":\"develop\"}")).settings();
        RepositorySettings b = RepositorySettings.read(repo("b", "{\"sourceBranch\":\"main\",\"mode\":\"push\"}")).settings();
        assertThat(a.sourceBranch()).isEqualTo("develop");
        assertThat(a.mode()).isNull();
        assertThat(b.sourceBranch()).isEqualTo("main");
        assertThat(b.mode()).isEqualTo(PublishMode.PUSH);
    }

    @Test
    void blankValuesCountAsUnset() throws Exception {
        RepositorySettings read = RepositorySettings.read(repo("blank", "{\"sourceBranch\":\"  \",\"mode\":null}")).settings();
        assertThat(read).isEqualTo(RepositorySettings.EMPTY);
    }

    @Test
    void aFileThatCannotBeUsedIsAProblemNotADefault() throws Exception {
        List<String[]> cases = List.of(
                new String[] {"{not json", "not valid JSON"},
                new String[] {"[1,2]", "must hold a JSON object"},
                new String[] {"{\"sourcebranch\":\"main\"}", "unknown key"},
                new String[] {"{\"sourceBranch\":5}", "must be text"},
                new String[] {"{\"sourceBranch\":\"-rf\"}", "not usable"},
                new String[] {"{\"sourceBranch\":\"a..b\"}", "not usable"},
                new String[] {"{\"sourceBranch\":\"a b\"}", "not usable"},
                new String[] {"{\"mode\":\"force\"}", "mode must be"},
                new String[] {"{\"pullRequestCommand\":\"gh\\nrm\"}", "one line"});
        int i = 0;
        for (String[] c : cases) {
            RepositorySettings.Read read = RepositorySettings.read(repo("bad" + i++, c[0]));
            assertThat(read.ok()).as(c[0]).isFalse();
            assertThat(read.problem()).as(c[0]).contains(c[1]).contains(".appmod/publish.json");
        }
    }

    @Test
    void branchNamesAreValidatedAsData() {
        for (String ok : List.of("main", "release/1.2", "feature/a_b-c", "v1.0.0")) {
            assertThat(BranchNames.valid(ok)).as(ok).isTrue();
        }
        for (String bad : List.of("", "-x", "--upload-pack=x", "a..b", "a//b", "/a", "a/", "a.lock", "a b", "a;b", "$(x)", ".hidden", "a/.b")) {
            assertThat(BranchNames.valid(bad)).as(bad).isFalse();
        }
    }
}
