package dev.subscriber.worktree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.store.Worktree;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses real git repositories in temporary folders: nothing here is simulated except one deliberately failing git command. */
class WorktreeServiceTest {

    private static final String ID = "0a1b2c3d-1111-2222-3333-444455556666";

    @TempDir
    Path temp;

    Path root;
    Path repo;
    String first;
    String second;
    WorktreeService service;

    static String git(Path folder, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out.strip();
    }

    static Path initRepository(Path folder) throws Exception {
        Files.createDirectories(folder);
        git(folder, "init", "-q");
        Files.writeString(folder.resolve("a.txt"), "one\n");
        git(folder, "add", "a.txt");
        git(folder, "commit", "-q", "-m", "first");
        return folder;
    }

    @BeforeEach
    void repository() throws Exception {
        root = temp.toRealPath();
        repo = initRepository(root.resolve("shop"));
        first = git(repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git(repo, "commit", "-q", "-am", "second");
        second = git(repo, "rev-parse", "HEAD");
        service = serviceFor(root);
    }

    private WorktreeService serviceFor(Path allowedRoot) throws IOException {
        Path noHooks = Files.createDirectories(temp.resolve("no-hooks"));
        return new WorktreeService(List.of(allowedRoot), new GitRunner(noHooks, 60));
    }

    private Path report(String commit) throws IOException {
        return Files.writeString(root.resolve("issues-" + UUID.randomUUID() + ".csv"), "file,line,title,fingerprint,commit\r\nx,1,t,f,\""
                + commit + "\"\r\n");
    }

    @Test
    void theWorktreeIsASiblingOnItsOwnBranchStartedFromTheAuditedCommit() throws Exception {
        WorktreeService.Created created = service.create(ID, repo, report(first));
        Worktree w = created.worktree();

        assertThat(w.name()).isEqualTo("shop-fix-0a1b2c3d");
        assertThat(w.branch()).isEqualTo("appmod/fix-0a1b2c3d");
        assertThat(Path.of(w.path())).isEqualTo(root.resolve("shop-fix-0a1b2c3d")).isDirectory();
        assertThat(w.baseCommit()).isEqualTo(first);
        assertThat(created.notes()).isEmpty();
        assertThat(git(Path.of(w.path()), "rev-parse", "HEAD")).isEqualTo(first);
        assertThat(git(Path.of(w.path()), "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("appmod/fix-0a1b2c3d");
        assertThat(Files.readString(Path.of(w.path()).resolve("a.txt")).replace("\r\n", "\n")).as("the audited code, not the later change")
                .isEqualTo("one\n");
        assertThat(git(repo, "worktree", "list")).contains("shop-fix-0a1b2c3d");
    }

    @Test
    void thePersonsCheckoutIsNotSwitchedAndItsFilesAndChangesAreUntouched() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nuncommitted edit\n");
        Files.writeString(repo.resolve("new-file.txt"), "untracked\n");
        git(repo, "add", "a.txt");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nuncommitted edit\nunstaged too\n");
        String branchBefore = git(repo, "rev-parse", "--abbrev-ref", "HEAD");
        String statusBefore = git(repo, "status", "--porcelain");
        String indexBefore = git(repo, "ls-files", "-s");
        String contentBefore = Files.readString(repo.resolve("a.txt"));

        service.create(ID, repo, report(first));

        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(branchBefore);
        assertThat(git(repo, "rev-parse", "HEAD")).isEqualTo(second);
        assertThat(git(repo, "status", "--porcelain")).isEqualTo(statusBefore);
        assertThat(git(repo, "ls-files", "-s")).isEqualTo(indexBefore);
        assertThat(Files.readString(repo.resolve("a.txt"))).isEqualTo(contentBefore);
        assertThat(Files.readString(repo.resolve("new-file.txt"))).isEqualTo("untracked\n");
    }

    @Test
    void aFolderOutsideTheAllowedRootsIsRefusedAndNothingIsCreated() throws Exception {
        // The repository itself is the allowed root, so its parent (where the worktree would go) is outside every root.
        WorktreeService narrow = serviceFor(repo);

        assertThatThrownBy(() -> narrow.create(ID, repo, report(first))).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("outside the allowed roots");
        assertThat(root.resolve("shop-fix-0a1b2c3d")).doesNotExist();
        assertThat(git(repo, "branch", "--list", "appmod/*")).isEmpty();
    }

    @Test
    void aFolderThatAlreadyExistsIsRefusedAndLeftAlone() throws Exception {
        Path existing = Files.createDirectories(root.resolve("shop-fix-0a1b2c3d"));
        Files.writeString(existing.resolve("precious.txt"), "keep");

        assertThatThrownBy(() -> service.create(ID, repo, report(first))).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("already exists");
        assertThat(existing.resolve("precious.txt")).hasContent("keep");
        assertThat(git(repo, "branch", "--list", "appmod/*")).isEmpty();
    }

    @Test
    void aBranchThatAlreadyExistsIsRefusedAndNoFolderIsMade() throws Exception {
        git(repo, "branch", "appmod/fix-0a1b2c3d");

        assertThatThrownBy(() -> service.create(ID, repo, report(first))).hasMessageContaining("branch appmod/fix-0a1b2c3d already exists");
        assertThat(root.resolve("shop-fix-0a1b2c3d")).doesNotExist();
    }

    @Test
    void theCommitInTheFormTheAuditToolWritesIsUsedAndADirtyAuditIsSaid() throws Exception {
        WorktreeService.Created clean = service.create(ID, repo, report("commit=" + first + ", dirty=false"));
        assertThat(clean.worktree().baseCommit()).isEqualTo(first);
        assertThat(clean.notes()).isEmpty();

        WorktreeService.Created dirty = service.create("99999999-aaaa-bbbb-cccc-dddddddddddd", repo, report("commit=" + first + ", dirty=true"));
        assertThat(dirty.worktree().baseCommit()).as("still the audited commit").isEqualTo(first);
        assertThat(dirty.notes()).singleElement().asString().contains("uncommitted changes").contains(first.substring(0, 7));
    }

    @Test
    void anAuditedCommitThatIsNotInTheRepositoryFallsBackToHeadAndSaysWhy() throws Exception {
        WorktreeService.Created created = service.create(ID, repo, report("1234567890abcdef1234567890abcdef12345678"));

        assertThat(created.worktree().baseCommit()).isEqualTo(second);
        assertThat(created.notes()).singleElement().asString().contains("report commit not used").contains("not in the repository");
    }

    @Test
    void aReportWithoutACommitOrThatIsNotACsvStartsFromHeadAndSaysWhy() throws Exception {
        Path noCommit = Files.writeString(root.resolve("plain.csv"), "file,line\nx,1\n");
        WorktreeService.Created a = service.create(ID, repo, noCommit);
        assertThat(a.worktree().baseCommit()).isEqualTo(second);
        assertThat(a.notes()).singleElement().asString().contains("report commit not used").contains("no commit");

        String other = "99999999-aaaa-bbbb-cccc-dddddddddddd";
        WorktreeService.Created b = service.create(other, repo, Files.writeString(root.resolve("r.json"), "{}"));
        assertThat(b.worktree().baseCommit()).isEqualTo(second);
        assertThat(b.notes()).singleElement().asString().contains("not a CSV");
        WorktreeService.Created c = service.create("88888888-aaaa-bbbb-cccc-dddddddddddd", repo, null);
        assertThat(c.worktree().baseCommit()).isEqualTo(second);
    }

    @Test
    void aRepositoryFolderNameWithShellCharactersGivesASafeNameAndWorks() throws Exception {
        Path odd = initRepository(root.resolve("r&d 100% '(1)"));

        WorktreeService.Created created = service.create(ID, odd.toRealPath(), null);

        assertThat(created.worktree().name()).isEqualTo("r_d_100____1_-fix-0a1b2c3d");
        assertThat(Path.of(created.worktree().path())).isDirectory().hasParent(root);
        assertThat(git(Path.of(created.worktree().path()), "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("appmod/fix-0a1b2c3d");
    }

    @Test
    void aRepositoryHookDoesNotRunBecauseOfTheEngine() throws Exception {
        Path marker = root.resolve("hook-ran.txt");
        String script = "#!/bin/sh\necho ran > '" + marker.toString().replace('\\', '/') + "'\n";
        Path hook = repo.resolve(".git/hooks/post-checkout");
        Files.writeString(hook, script);
        hook.toFile().setExecutable(true);

        // Sanity: git itself runs this hook when it creates a worktree, so the test would notice if the hook were run.
        git(repo, "worktree", "add", "-q", "-b", "sanity", root.resolve("sanity").toString(), "HEAD");
        assertThat(marker).as("the hook works when git is called plainly").exists();
        Files.delete(marker);

        service.create(ID, repo, report(first));

        assertThat(marker).as("the engine disabled the hooks").doesNotExist();
    }

    @Test
    void aFailureAfterThePartOfTheWorkLeavesNoFolderBranchOrWorktreeEntry() throws Exception {
        Path noHooks = Files.createDirectories(temp.resolve("no-hooks-failing"));
        GitRunner real = new GitRunner(noHooks, 60);
        GitRunner failingAfterCreating = new GitRunner(noHooks, 60) {
            @Override
            Result run(Path workingDirectory, String... args) throws IOException {
                Result result = real.run(workingDirectory, args);
                if (args.length > 1 && args[0].equals("worktree") && args[1].equals("add")) {
                    return new Result(128, "fatal: simulated failure after the folder and the branch were made\n");
                }
                return result;
            }
        };
        WorktreeService failing = new WorktreeService(List.of(root), failingAfterCreating);

        assertThatThrownBy(() -> failing.create(ID, repo, report(first))).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("git could not create the worktree").hasMessageContaining("simulated failure");

        assertThat(root.resolve("shop-fix-0a1b2c3d")).doesNotExist();
        assertThat(git(repo, "branch", "--list", "appmod/*")).isEmpty();
        assertThat(git(repo, "worktree", "list").lines().count()).as("only the checkout itself").isEqualTo(1);
    }

    @Test
    void aRepositoryWithoutACommitCannotGetAWorktree() throws Exception {
        Path empty = root.resolve("empty");
        Files.createDirectories(empty);
        git(empty, "init", "-q");

        assertThatThrownBy(() -> service.create(ID, empty.toRealPath(), null)).hasMessageContaining("no commit");
    }

    @Test
    void anIdThatIsNotAUuidIsRefusedBeforeGitIsRun() {
        for (String id : new String[] {null, "", "not-an-id", "0a1b2c3d", ID + "x", "0A1B2C3D-1111-2222-3333-444455556666"}) {
            assertThatThrownBy(() -> service.create(id, repo, null)).as(String.valueOf(id)).hasMessageContaining("not a UUID");
        }
    }

    @Test
    void theNameKeepsLettersDigitsAndFewMarksAndIsCut() {
        assertThat(WorktreeService.safeName("shop")).isEqualTo("shop");
        assertThat(WorktreeService.safeName("my.shop_v2-x")).isEqualTo("my.shop_v2-x");
        assertThat(WorktreeService.safeName("r&d 100% '(1)")).isEqualTo("r_d_100____1_");
        assertThat(WorktreeService.safeName("..")).isEqualTo("repository");
        assertThat(WorktreeService.safeName("é")).isEqualTo("_");
        assertThat(WorktreeService.safeName("x".repeat(200))).hasSize(80);
    }

    @Test
    void theCommitCountIsZeroBeforeAnyCommitAndCountsEachCommitOnTheBranch() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();
        Path folder = Path.of(w.path());
        assertThat(service.commitCount(w.path(), w.baseCommit(), w.branch())).isZero();

        Files.writeString(folder.resolve("fix1.txt"), "1\n");
        git(folder, "add", "fix1.txt");
        git(folder, "commit", "-q", "-m", "fix one");
        assertThat(service.commitCount(w.path(), w.baseCommit(), w.branch())).isEqualTo(1);

        Files.writeString(folder.resolve("fix2.txt"), "2\n");
        git(folder, "add", "fix2.txt");
        git(folder, "commit", "-q", "-m", "fix two");
        assertThat(service.commitCount(w.path(), w.baseCommit(), w.branch())).isEqualTo(2);
        assertThat(git(repo, "rev-parse", "HEAD")).as("the checkout's own branch got none of them").isEqualTo(second);
    }

    @Test
    void theCountIsEmptyWhenTheWorktreeFolderIsGoneOrIsNotARepositoryOrTheInputsAreWrong() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();
        Path plain = Files.createDirectories(root.resolve("plain-folder"));

        assertThat(service.commitCount(plain.toString(), w.baseCommit(), w.branch())).as("not a repository").isNull();
        assertThat(service.commitCount(w.path(), "0000000000000000000000000000000000000000", w.branch())).as("unknown base").isNull();
        assertThat(service.commitCount(w.path(), "--output=x", w.branch())).as("not a commit id").isNull();
        assertThat(service.commitCount(w.path(), w.baseCommit(), "main; rm -rf /")).as("not our branch form").isNull();
        assertThat(service.commitCount(null, w.baseCommit(), w.branch())).isNull();
        assertThat(service.commitCount(w.path(), null, w.branch())).isNull();

        git(repo, "worktree", "remove", "--force", w.path());
        assertThat(service.commitCount(w.path(), w.baseCommit(), w.branch())).as("folder gone").isNull();
    }

    private static String listed(Path repository) throws Exception {
        return git(repository, "worktree", "list", "--porcelain");
    }

    @Test
    void removingDropsTheFolderAndTheAgentsReportsButKeepsTheBranchAndItsCommits() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();
        Path folder = Path.of(w.path());
        Files.writeString(folder.resolve("fix.txt"), "fixed\n");
        git(folder, "add", "fix.txt");
        git(folder, "commit", "-q", "-m", "fix one");
        Files.createDirectories(folder.resolve(".appmod"));
        Files.writeString(folder.resolve(".appmod/result.json"), "{}");
        Files.writeString(folder.resolve(".appmod/summary.md"), "done");

        service.remove(repo, w.path(), w.branch());

        assertThat(folder).doesNotExist();
        assertThat(listed(repo)).doesNotContain("shop-fix-0a1b2c3d");
        assertThat(git(repo, "rev-parse", "--verify", "refs/heads/" + w.branch())).as("the branch stays").isNotEmpty();
        assertThat(git(repo, "rev-list", "--count", first + ".." + w.branch())).as("and its commits").isEqualTo("1");
        assertThat(git(repo, "rev-parse", "HEAD")).as("the checkout is untouched").isEqualTo(second);
    }

    @Test
    void uncommittedWorkOutsideTheAgentsReportsIsNeverThrownAway() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();
        Path folder = Path.of(w.path());
        Files.createDirectories(folder.resolve(".appmod"));
        Files.writeString(folder.resolve(".appmod/result.json"), "{}");

        Files.writeString(folder.resolve("new-file.txt"), "not committed\n");
        assertThatThrownBy(() -> service.remove(repo, w.path(), w.branch())).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("uncommitted work").hasMessageContaining("new-file.txt");
        assertThat(folder.resolve("new-file.txt")).as("untracked file").exists();

        Files.delete(folder.resolve("new-file.txt"));
        Files.writeString(folder.resolve("a.txt"), "changed\n");
        assertThatThrownBy(() -> service.remove(repo, w.path(), w.branch())).hasMessageContaining("uncommitted work");
        assertThat(folder.resolve("a.txt")).as("modified tracked file").exists();

        git(folder, "checkout", "--", "a.txt");
        Files.writeString(folder.resolve("staged.txt"), "staged\n");
        git(folder, "add", "staged.txt");
        assertThatThrownBy(() -> service.remove(repo, w.path(), w.branch())).hasMessageContaining("uncommitted work");
        assertThat(folder).as("nothing was removed").exists();

        git(folder, "commit", "-q", "-m", "now committed");
        service.remove(repo, w.path(), w.branch());
        assertThat(folder).doesNotExist();
    }

    @Test
    void theRepositorysOwnCheckoutIsNeverRemovedEvenWhenItsNameAndBranchLookLikeAWorktrees() throws Exception {
        Path lookalike = initRepository(root.resolve("shop-fix-0a1b2c3d"));
        git(lookalike, "branch", "appmod/fix-0a1b2c3d");

        assertThatThrownBy(() -> service.remove(lookalike, lookalike.toString(), "appmod/fix-0a1b2c3d"))
                .isInstanceOf(WorktreeService.WorktreeException.class).hasMessageContaining("does not list");
        assertThat(lookalike.resolve("a.txt")).exists();

        assertThatThrownBy(() -> service.remove(repo, repo.toString(), "appmod/fix-0a1b2c3d"))
                .hasMessageContaining("does not have the name");
        assertThat(repo.resolve("a.txt")).exists();
    }

    @Test
    void aFolderGitDoesNotListIsNotRemoved() throws Exception {
        Path plain = Files.createDirectories(root.resolve("shop-fix-0a1b2c3d"));
        Files.writeString(plain.resolve("precious.txt"), "mine\n");

        assertThatThrownBy(() -> service.remove(repo, plain.toString(), "appmod/fix-0a1b2c3d"))
                .isInstanceOf(WorktreeService.WorktreeException.class).hasMessageContaining("does not list");
        assertThat(plain.resolve("precious.txt")).exists();
    }

    @Test
    void aNameOrBranchThatIsNotTheEnginesFormIsRefused() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();

        assertThatThrownBy(() -> service.remove(repo, w.path(), "main; rm -rf /")).hasMessageContaining("not one the engine made");
        assertThatThrownBy(() -> service.remove(repo, w.path(), null)).hasMessageContaining("not one the engine made");
        assertThatThrownBy(() -> service.remove(repo, w.path(), "appmod/fix-ffffffff")).hasMessageContaining("does not have the name");
        assertThatThrownBy(() -> service.remove(repo, null, w.branch())).hasMessageContaining("not one the engine made");
        assertThat(Path.of(w.path())).exists();
    }

    @Test
    void aFolderOutsideTheAllowedRootsIsRefused() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();
        WorktreeService narrow = serviceFor(Files.createDirectories(root.resolve("elsewhere")));

        assertThatThrownBy(() -> narrow.remove(repo, w.path(), w.branch())).hasMessageContaining("outside the allowed roots");
        assertThat(Path.of(w.path())).exists();
    }

    @Test
    void aFolderThatIsAlreadyGoneIsReported() throws Exception {
        Worktree w = service.create(ID, repo, report(first)).worktree();
        git(repo, "worktree", "remove", "--force", w.path());

        assertThatThrownBy(() -> service.remove(repo, w.path(), w.branch())).hasMessageContaining("already gone");
    }

    private Path deepRepository() throws Exception {
        // A short repository name under a long parent: the worktree folder is 202 characters (over 200) while git's own folder inside the
        // repository, about 222, is still under its limit of 230, so only the folder rule applies.
        int parentLength = 183;
        Path parent = Files.createDirectories(root.resolve("d".repeat(parentLength - root.toString().length() - 1)));
        return initRepository(parent.resolve("repo1"));
    }

    @Test
    void aWorktreeFolderThatIsTooLongForWindowsIsRefusedBeforeGitRunsAndNothingIsLeftBehind() throws Exception {
        Path deep = deepRepository();
        WorktreeService windows = new WorktreeService(List.of(root), new GitRunner(temp.resolve("no-hooks"), 60), true);

        assertThatThrownBy(() -> windows.create(ID, deep, null)).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("characters long").hasMessageContaining("limit of 200").hasMessageContaining("shorter");
        assertThat(git(deep, "branch", "--list", "appmod/*")).as("no branch was made").isEmpty();
        assertThat(git(deep, "worktree", "list")).as("only the checkout").doesNotContain("-fix-");
    }

    @Test
    void aRepositoryPathThatLeavesNoRoomForGitsOwnWorktreeFilesIsRefusedOnWindowsEvenWhenTheFolderFits() throws Exception {
        // The folder is about 150 characters, but git keeps <repository>/.git/worktrees/<name> too, and that path is the longer one.
        Path parent = Files.createDirectories(root.resolve("p".repeat(Math.max(60 - root.toString().length(), 1) + 40)));
        Path longName = initRepository(parent.resolve("n".repeat(80)));
        WorktreeService windows = new WorktreeService(List.of(root), new GitRunner(temp.resolve("no-hooks"), 60), true);
        int admin = longName.toString().length() + "/.git/worktrees/".length() + ("n".repeat(80) + "-fix-0a1b2c3d").length();

        assertThat(admin).as("the setup really is over the admin limit").isGreaterThan(WorktreeService.MAX_WINDOWS_ADMIN);
        assertThatThrownBy(() -> windows.create(ID, longName, null)).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("worktree's own files").hasMessageContaining("limit of 230");
        assertThat(git(longName, "branch", "--list", "appmod/*")).isEmpty();
    }

    @Test
    void whenGitItselfRefusesTheReasonIsGitsErrorLineAndNotThePreparingLine() throws Exception {
        Files.createDirectories(root.resolve("taken"));
        Path folder = root.resolve("shop-fix-" + ID.substring(0, 8));
        Files.writeString(folder, "a file where the worktree folder should go");
        git(repo, "branch", "appmod/fix-" + ID.substring(0, 8));

        assertThatThrownBy(() -> service.create(ID, repo, report(first))).isInstanceOf(WorktreeService.WorktreeException.class)
                .hasMessageContaining("already exists").hasMessageNotContaining("Preparing worktree");
    }
}
