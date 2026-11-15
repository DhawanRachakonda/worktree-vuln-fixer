package dev.subscriber.worktree;

import static dev.subscriber.worktree.WorktreeServiceTest.git;
import static dev.subscriber.worktree.WorktreeServiceTest.initRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.worktree.WorktreeProvisioner.SourceRequest;
import dev.subscriber.worktree.WorktreeProvisioner.WorktreeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The source branch of a session, with real git repositories: one test for each scenario of the capability source-branch-selection. */
class SourceBranchTest {

    private static final String ID = "0a1b2c3d-1111-2222-3333-444455556666";

    @TempDir
    Path temp;

    Path root;
    Path repo;
    WorktreeService service;
    GitRunner runner;
    String mainTip;
    String developTip;

    @BeforeEach
    void repository() throws Exception {
        root = temp.toRealPath();
        repo = initRepository(root.resolve("shop"));
        git(repo, "branch", "-M", "main");
        mainTip = git(repo, "rev-parse", "HEAD");
        git(repo, "checkout", "-q", "-b", "develop");
        Files.writeString(repo.resolve("dev.txt"), "dev\n");
        git(repo, "add", "dev.txt");
        git(repo, "commit", "-q", "-m", "develop work");
        developTip = git(repo, "rev-parse", "HEAD");
        git(repo, "checkout", "-q", "-b", "feature/x");
        Files.writeString(repo.resolve("x.txt"), "x\n");
        git(repo, "add", "x.txt");
        git(repo, "commit", "-q", "-m", "feature work");
        runner = new GitRunner(temp.resolve("no-hooks"), 60);
        service = new WorktreeService(List.of(root), runner);
    }

    private Path report(String commit) throws Exception {
        return Files.writeString(root.resolve("report.csv"), "file,line,commit\nx,1," + commit + "\n");
    }

    private void saved(Path repository, String json) throws Exception {
        Files.createDirectories(repository.resolve(".appmod"));
        Files.writeString(repository.resolve(RepositorySettings.LOCATION), json);
    }

    private Path bareOrigin() throws Exception {
        Path bare = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        return bare;
    }

    // --- choosing the source branch ----------------------------------------------------------------------------------------

    @Test
    void aBranchGivenWhenPublishingIsTheBase() throws Exception {
        git(repo, "branch", "release/1.2", mainTip);
        var created = service.create(ID, repo, report(developTip), new SourceRequest("release/1.2", false));

        assertThat(created.worktree().baseCommit()).isEqualTo(mainTip);
        assertThat(created.source().branch()).isEqualTo("release/1.2");
        assertThat(created.source().choice()).isEqualTo("publish");
        assertThat(created.source().localChanges()).isFalse();
        assertThat(created.notes()).anyMatch(n -> n.contains("report commit not used"));
    }

    @Test
    void theSavedBranchBeatsTheReportsCommitAndAnEventSaysSo() throws Exception {
        saved(repo, "{\"sourceBranch\":\"develop\"}");
        var created = service.create(ID, repo, report(mainTip), SourceRequest.NONE);

        assertThat(created.worktree().baseCommit()).isEqualTo(developTip);
        assertThat(created.source().choice()).isEqualTo("saved");
        assertThat(created.source().branch()).isEqualTo("develop");
        assertThat(created.notes()).anyMatch(n -> n.contains("report commit not used") && n.contains("develop"));
    }

    @Test
    void theGivenBranchBeatsTheSavedOne() throws Exception {
        saved(repo, "{\"sourceBranch\":\"develop\"}");
        var created = service.create(ID, repo, null, new SourceRequest("main", false));
        assertThat(created.worktree().baseCommit()).isEqualTo(mainTip);
        assertThat(created.source().choice()).isEqualTo("publish");
    }

    @Test
    void theReportsCommitIsUsedWhenNothingIsGivenOrSaved() throws Exception {
        var created = service.create(ID, repo, report(mainTip), SourceRequest.NONE);
        assertThat(created.worktree().baseCommit()).isEqualTo(mainTip);
        assertThat(created.source().choice()).isEqualTo("report-commit");
        assertThat(created.source().branch()).isNull();
    }

    @Test
    void withNoChoiceTheDefaultBranchIsUsedNotTheCheckedOutFeatureBranch() throws Exception {
        var created = service.create(ID, repo, null, SourceRequest.NONE);

        assertThat(created.worktree().baseCommit()).isEqualTo(mainTip);
        assertThat(created.source().choice()).isEqualTo("default-branch");
        assertThat(created.source().branch()).isEqualTo("main");
        assertThat(created.notes()).anyMatch(n -> n.contains("default branch main") && n.contains("local guess"));
    }

    @Test
    void anUnusableGivenOrSavedBranchIsAnErrorAndCreatesNothing() throws Exception {
        assertThatThrownBy(() -> service.create(ID, repo, report(mainTip), new SourceRequest("nope", false)))
                .isInstanceOf(WorktreeException.class).hasMessageContaining("nope").hasMessageContaining("does not exist").hasMessageContaining("given");
        assertThatThrownBy(() -> service.create(ID, repo, null, new SourceRequest("--upload-pack=x", false)))
                .isInstanceOf(WorktreeException.class).hasMessageContaining("not usable");
        saved(repo, "{\"sourceBranch\":\"gone\"}");
        assertThatThrownBy(() -> service.create(ID, repo, report(mainTip), SourceRequest.NONE))
                .isInstanceOf(WorktreeException.class).hasMessageContaining("gone").hasMessageContaining("saved");
        assertThat(root.resolve("shop-fix-0a1b2c3d")).doesNotExist();
        assertThat(git(repo, "branch", "--list", "appmod/*")).isEmpty();
    }

    @Test
    void anInvalidSettingsFileMakesTheSessionFailWithItsReason() throws Exception {
        saved(repo, "{not json");
        assertThatThrownBy(() -> service.create(ID, repo, null, SourceRequest.NONE))
                .isInstanceOf(WorktreeException.class).hasMessageContaining(".appmod/publish.json").hasMessageContaining("not valid JSON");
    }

    @Test
    void twoRepositoriesWithDifferentSavedBranches() throws Exception {
        Path other = initRepository(root.resolve("blog"));
        git(other, "branch", "-M", "main");
        String otherMain = git(other, "rev-parse", "HEAD");
        saved(repo, "{\"sourceBranch\":\"develop\"}");
        saved(other, "{\"sourceBranch\":\"main\"}");

        assertThat(service.create(ID, repo, null, SourceRequest.NONE).worktree().baseCommit()).isEqualTo(developTip);
        assertThat(service.create(ID, other, null, SourceRequest.NONE).worktree().baseCommit()).isEqualTo(otherMain);
    }

    @Test
    void aRemoteTrackingBranchIsUsedWhenThereIsNoLocalOne() throws Exception {
        bareOrigin();
        git(repo, "push", "-q", "origin", "develop:remote-only");
        git(repo, "fetch", "-q", "origin");
        var created = service.create(ID, repo, null, new SourceRequest("remote-only", false));
        assertThat(created.worktree().baseCommit()).isEqualTo(developTip);
    }

    // --- default branch detection ------------------------------------------------------------------------------------------

    @Test
    void theRemotesDefaultKnownLocallyIsTheDefault() throws Exception {
        Path bare = bareOrigin();
        git(repo, "push", "-q", "origin", "develop");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/develop");
        git(repo, "fetch", "-q", "origin");
        git(repo, "remote", "set-head", "origin", "develop");

        var found = DefaultBranch.detect(runner, repo);
        assertThat(found.branch()).isEqualTo("develop");
        assertThat(found.how()).isEqualTo(DefaultBranch.How.REMOTE_DEFAULT);
    }

    @Test
    void theRemoteIsAskedWhenOriginHeadIsNotKnownLocally() throws Exception {
        Path bare = bareOrigin();
        git(repo, "push", "-q", "origin", "develop");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/develop");

        var found = DefaultBranch.detect(runner, repo);
        assertThat(found.branch()).isEqualTo("develop");
        assertThat(found.how()).isEqualTo(DefaultBranch.How.REMOTE_DEFAULT);
    }

    @Test
    void withoutARemoteMainIsALocalGuess() throws Exception {
        var found = DefaultBranch.detect(runner, repo);
        assertThat(found.branch()).isEqualTo("main");
        assertThat(found.how()).isEqualTo(DefaultBranch.How.LOCAL_GUESS);
    }

    @Test
    void masterIsUsedWhenThereIsNoMainAndTheCheckedOutBranchWhenThereIsNeither() throws Exception {
        git(repo, "branch", "-m", "main", "master");
        assertThat(DefaultBranch.detect(runner, repo).branch()).isEqualTo("master");
        git(repo, "branch", "-m", "master", "trunk");
        var found = DefaultBranch.detect(runner, repo);
        assertThat(found.branch()).isEqualTo("feature/x");
        assertThat(found.how()).isEqualTo(DefaultBranch.How.NO_REMOTE);
    }

    @Test
    void detectionNeverFetches() throws Exception {
        bareOrigin();
        git(repo, "push", "-q", "origin", "main");
        git(repo, "update-ref", "-d", "refs/remotes/origin/main");
        DefaultBranch.detect(runner, repo);
        assertThat(git(repo, "branch", "-r")).isEmpty();
    }

    // --- local changes -----------------------------------------------------------------------------------------------------

    private void ignoreLogsThenEdit() throws Exception {
        Files.writeString(repo.resolve(".gitignore"), "*.log\n");
        git(repo, "add", ".gitignore");
        git(repo, "commit", "-q", "-m", "ignore logs");
        Files.writeString(repo.resolve("x.txt"), "x\nedited\n");
        Files.writeString(repo.resolve("fresh.txt"), "new\n");
        Files.writeString(repo.resolve("debug.log"), "ignored\n");
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/result.json"), "{}");
    }

    @Test
    void localChangesAreIncludedAndTheCheckoutIsUntouched() throws Exception {
        ignoreLogsThenEdit();
        String tip = git(repo, "rev-parse", "HEAD");
        String statusBefore = git(repo, "status", "--porcelain", "-uall");
        String indexBefore = git(repo, "ls-files", "--stage");

        var created = service.create(ID, repo, null, new SourceRequest(null, true));

        Path work = Path.of(created.worktree().path());
        assertThat(work.resolve("x.txt")).hasContent("x\nedited\n");
        assertThat(work.resolve("fresh.txt")).hasContent("new\n");
        assertThat(work.resolve("debug.log")).doesNotExist();
        assertThat(work.resolve(".appmod")).doesNotExist();
        assertThat(created.source().localChanges()).isTrue();
        assertThat(created.source().branch()).isEqualTo("feature/x");
        assertThat(git(repo, "log", "-1", "--format=%P", created.worktree().baseCommit())).isEqualTo(tip);
        assertThat(git(repo, "log", "-1", "--format=%s", created.worktree().baseCommit())).contains("Uncommitted local work");
        // the checkout: same branch, same commit, same changes, same index
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/x");
        assertThat(git(repo, "rev-parse", "HEAD")).isEqualTo(tip);
        assertThat(git(repo, "status", "--porcelain", "-uall")).isEqualTo(statusBefore);
        assertThat(git(repo, "ls-files", "--stage")).isEqualTo(indexBefore);
    }

    @Test
    void withoutTheOptionTheWorktreeHasNoLocalChanges() throws Exception {
        ignoreLogsThenEdit();
        var created = service.create(ID, repo, null, SourceRequest.NONE);
        Path work = Path.of(created.worktree().path());
        assertThat(work.resolve("fresh.txt")).doesNotExist();
        assertThat(created.source().localChanges()).isFalse();
    }

    @Test
    void aCleanCheckoutStartsFromTheTipAndSaysThereWasNothingToInclude() throws Exception {
        String tip = git(repo, "rev-parse", "HEAD");
        var created = service.create(ID, repo, null, new SourceRequest(null, true));
        assertThat(created.worktree().baseCommit()).isEqualTo(tip);
        assertThat(created.source().localChanges()).isFalse();
        assertThat(created.notes()).anyMatch(n -> n.contains("nothing to include"));
    }

    @Test
    void aSourceBranchAndLocalChangesCannotBeCombined() {
        assertThatThrownBy(() -> service.create(ID, repo, null, new SourceRequest("main", true)))
                .isInstanceOf(WorktreeException.class).hasMessageContaining("cannot be combined");
    }

    /** The real case that a first version failed on: appmod init puts .appmod/ in .gitignore, and git writes line-ending warnings. */
    @Test
    void localChangesWorkWhenAppmodIsIgnoredAndGitWritesWarnings() throws Exception {
        git(repo, "config", "core.autocrlf", "true");
        Files.writeString(repo.resolve(".gitignore"), ".appmod/\n");
        git(repo, "add", ".gitignore");
        git(repo, "commit", "-q", "-m", "ignore .appmod, as appmod init does");
        Files.writeString(repo.resolve("x.txt"), "x\nedited\n");
        Files.writeString(repo.resolve("notes.txt"), "untracked\n");
        Files.createDirectories(repo.resolve(".appmod/security-audit"));
        Files.writeString(repo.resolve(".appmod/security-audit/issues.csv"), "file,line,title\n");

        var created = service.create(ID, repo, null, new SourceRequest(null, true));

        Path work = Path.of(created.worktree().path());
        assertThat(created.source().localChanges()).isTrue();
        assertThat(work.resolve("x.txt")).content().contains("edited");
        assertThat(work.resolve("notes.txt")).exists();
        assertThat(work.resolve(".appmod")).doesNotExist();
    }

    @Test
    void anAppmodFolderThatIsNotIgnoredStaysOutOfTheSnapshotToo() throws Exception {
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/result.json"), "{}");
        Files.writeString(repo.resolve("fresh.txt"), "new\n");

        var created = service.create(ID, repo, null, new SourceRequest(null, true));

        assertThat(Path.of(created.worktree().path()).resolve("fresh.txt")).exists();
        assertThat(Path.of(created.worktree().path()).resolve(".appmod")).doesNotExist();
    }

    @Test
    void theReasonOfAGitFailureIsItsErrorLineNotAWarningThatCameFirst() {
        var result = new GitRunner.Result(1, "warning: in the working copy of 'a.txt', LF will be replaced by CRLF\n"
                + "hint: something helpful\nfatal: could not do the thing\nwarning: later\n");
        assertThat(result.reason()).isEqualTo("fatal: could not do the thing");
        assertThat(new GitRunner.Result(1, "warning: x\nhint: y\nthe only plain line\n").reason()).isEqualTo("the only plain line");
        assertThat(new GitRunner.Result(1, "").reason()).isEmpty();
    }

    @Test
    void stagedChangesAreIncludedToo() throws Exception {
        Files.writeString(repo.resolve("staged.txt"), "s\n");
        git(repo, "add", "staged.txt");
        var created = service.create(ID, repo, null, new SourceRequest(null, true));
        assertThat(Path.of(created.worktree().path()).resolve("staged.txt")).hasContent("s\n");
        assertThat(git(repo, "diff", "--cached", "--name-only")).isEqualTo("staged.txt");
    }
}
