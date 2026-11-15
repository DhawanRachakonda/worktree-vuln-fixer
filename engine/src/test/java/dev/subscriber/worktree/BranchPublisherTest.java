package dev.subscriber.worktree;

import static dev.subscriber.worktree.WorktreeServiceTest.git;
import static dev.subscriber.worktree.WorktreeServiceTest.initRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.PublishMode;
import dev.subscriber.console.CommandFinder;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionState;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Pushing a session's branch and the pull-request command, with real git, a local bare remote and a stub program. */
class BranchPublisherTest {

    private static final String ID = "0a1b2c3d-1111-2222-3333-444455556666";
    private static final String BRANCH = "appmod/fix-0a1b2c3d";

    @TempDir
    Path temp;

    Path root;
    Path repo;
    Path bare;
    Path work;
    String base;
    FixSessionRepository sessions = mock(FixSessionRepository.class);
    SessionFolders folders;

    @BeforeEach
    void setUp() throws Exception {
        root = temp.toRealPath();
        repo = initRepository(root.resolve("shop"));
        git(repo, "branch", "-M", "main");
        bare = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "-q", "origin", "main");
        var created = new WorktreeService(List.of(root), new GitRunner(root.resolve("no-hooks"), 60)).create(ID, repo, null,
                WorktreeProvisioner.SourceRequest.NONE);
        work = Path.of(created.worktree().path());
        base = created.worktree().baseCommit();
        Files.writeString(work.resolve("fix.txt"), "fixed\n");
        git(work, "add", "fix.txt");
        git(work, "commit", "-q", "-m", "fix one");
    }

    private SubscriberProperties properties(PublishMode mode, String command) {
        return new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()), List.of(),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.WORKTREE, SubscriberProperties.AgentTool.COPILOT, new SubscriberProperties.Claude("claude"),
                new SubscriberProperties.Publish(mode, command));
    }

    private FixSession session(SessionState state, Integer commits, boolean localChanges, String sourceBranch) {
        return new FixSession(ID, 1, state, "securityaudit.issuefixesAgent", "Security audit", "r", null, repo.toString(), "appmod-harness",
                "headless", "copilot", Instant.now(), Instant.now(), Instant.now(), null, null, 0, "h", "e", false, "succeeded", null,
                work.getFileName().toString(), work.toString(), BRANCH, base, commits, null, sourceBranch, sourceBranch == null ? null : "publish",
                localChanges, null, null, null);
    }

    private BranchPublisher publisher(PublishMode mode, String command) {
        SubscriberProperties props = properties(mode, command);
        folders = new SessionFolders(props);
        // the stub program is found on the folder of the running Java, which is where the test's own java is
        String javaDir = Path.of(ProcessHandle.current().info().command().orElseThrow()).getParent().toString();
        return new BranchPublisher(props, sessions, folders, Clock.systemUTC(), new GitRunner(root.resolve("no-hooks"), 60),
                new CommandFinder(javaDir, System.getProperty("os.name").toLowerCase().contains("win"), null));
    }

    private String remoteBranches() throws Exception {
        return git(bare, "branch", "--list");
    }

    private List<String> events() {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions, org.mockito.Mockito.atLeast(0)).appendEvent(eq(ID), any(), any(), detail.capture());
        return detail.getAllValues();
    }

    private void given(FixSession session) {
        when(sessions.find(ID)).thenReturn(Optional.of(session));
    }

    // --- 4.1 ----------------------------------------------------------------------------------------------------------------

    @Test
    void aSucceededSessionIsPushedInPushMode() throws Exception {
        given(session(SessionState.DONE, 1, false, "main"));
        var outcome = publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow();

        assertThat(outcome.publishState()).isEqualTo("pushed");
        assertThat(remoteBranches()).contains(BRANCH);
        assertThat(git(bare, "rev-parse", BRANCH)).isEqualTo(git(work, "rev-parse", "HEAD"));
        verify(sessions).setPublishState(ID, "pushed");
        assertThat(events()).anyMatch(e -> e.contains("pushed " + BRANCH));
        verify(sessions, never()).setPullRequest(anyString(), anyString(), any());
    }

    @Test
    void aPartiallySucceededSessionIsPushedToo() throws Exception {
        given(session(SessionState.PARTIAL, 1, false, "main"));
        assertThat(publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow().publishState()).isEqualTo("pushed");
        assertThat(remoteBranches()).contains(BRANCH);
    }

    @Test
    void modeNoneMakesNoCallToTheRemoteAndRecordsThatNothingWasPublished() throws Exception {
        // a remote that would fail loudly if it were contacted
        git(repo, "remote", "set-url", "origin", root.resolve("does-not-exist.git").toString());
        git(work, "remote", "set-url", "origin", root.resolve("does-not-exist.git").toString());
        given(session(SessionState.DONE, 1, false, "main"));

        assertThat(publisher(PublishMode.NONE, null).afterSession(ID)).isEmpty();

        verify(sessions).setPublishState(ID, "none");
        assertThat(events()).isEmpty();
    }

    @Test
    void theRepositorysOwnModeBeatsTheEnginesDefault() throws Exception {
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/publish.json"), "{\"mode\":\"push\"}");
        given(session(SessionState.DONE, 1, false, "main"));
        assertThat(publisher(PublishMode.NONE, null).afterSession(ID).orElseThrow().publishState()).isEqualTo("pushed");
        assertThat(remoteBranches()).contains(BRANCH);
    }

    @Test
    void nothingIsPushedForAnotherFinalStateOrForASessionWithoutCommits() throws Exception {
        for (SessionState state : List.of(SessionState.ERROR, SessionState.NEEDS_INPUT, SessionState.INTERRUPTED, SessionState.REJECTED)) {
            given(session(state, 1, false, "main"));
            assertThat(publisher(PublishMode.PUSH, null).afterSession(ID)).as(state.name()).isEmpty();
        }
        given(session(SessionState.DONE, 0, false, "main"));
        assertThat(publisher(PublishMode.PUSH, null).afterSession(ID)).isEmpty();
        given(session(SessionState.DONE, null, false, "main"));
        assertThat(publisher(PublishMode.PUSH, null).afterSession(ID)).isEmpty();
        assertThat(remoteBranches()).doesNotContain(BRANCH);
        verify(sessions, never()).setPublishState(anyString(), anyString());
    }

    @Test
    void aRemoteThatRefusesTheBranchIsAFailureWithTheReasonAndTheLocalBranchIsKept() throws Exception {
        // the remote already has a branch of that name with a different history, so a push that does not force is refused. The history must
        // really differ: two repositories made in the same second hold an identical first commit (same content, author, message and time),
        // and then the session's branch would simply be a fast-forward of the remote's, which git accepts.
        Path other = initRepository(root.resolve("other"));
        Files.writeString(other.resolve("b.txt"), "a different history\n");
        git(other, "add", "b.txt");
        git(other, "commit", "-q", "-m", "different");
        git(other, "branch", "-M", BRANCH);
        git(other, "push", "-q", bare.toString(), BRANCH);
        given(session(SessionState.DONE, 1, false, "main"));

        var outcome = publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow();

        assertThat(outcome.publishState()).isEqualTo("push_failed");
        verify(sessions).setPublishState(ID, "push_failed");
        assertThat(events()).anyMatch(e -> e.contains("push failed") && e.contains("publish-branch " + ID + " --yes"));
        assertThat(git(work, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo(BRANCH);
        assertThat(git(bare, "log", "-1", "--format=%s", BRANCH)).as("the remote's branch was not overwritten").isEqualTo("different");
    }

    @Test
    void aRepositoryWithoutAnOriginIsAFailureNotACrash() throws Exception {
        git(repo, "remote", "remove", "origin");
        given(session(SessionState.DONE, 1, false, "main"));
        assertThat(publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow().publishState()).isEqualTo("push_failed");
    }

    @Test
    void credentialsInAUrlNeverReachAnEventOrTheOutcome() throws Exception {
        git(repo, "remote", "set-url", "origin", "http://alice:s3cr3t-token@127.0.0.1:1/shop.git");
        given(session(SessionState.DONE, 1, false, "main"));

        var outcome = publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow();

        assertThat(outcome.publishState()).isEqualTo("push_failed");
        assertThat(outcome.message()).doesNotContain("s3cr3t-token").doesNotContain("alice");
        assertThat(events()).noneMatch(e -> e.contains("s3cr3t-token") || e.contains("alice:"));
    }

    @Test
    void theCleanerRemovesUserInfoAndControlCharacters() {
        assertThat(BranchPublisher.clean("fatal: unable to access 'https://bob:pw@host/x.git/': nope\u001b[31m"))
                .doesNotContain("bob").doesNotContain("pw@").contains("https://***@host/x.git").doesNotContain("\u001b");
    }

    // --- 4.2 ----------------------------------------------------------------------------------------------------------------

    private Path stub(String printed, int exitCode) throws Exception {
        Path source = temp.resolve("Stub.java");
        Files.writeString(source, """
                import java.nio.file.*;
                import java.util.*;

                public class Stub {
                    public static void main(String[] args) throws Exception {
                        Files.write(Path.of(System.getProperty("stub.out", "%s")), Arrays.asList(args));
                        Files.writeString(Path.of("%s"), Files.exists(Path.of(args[args.length - 1])) ? Files.readString(Path.of(args[args.length - 1])) : "");
                        System.out.println("%s");
                        System.exit(%d);
                    }
                }
                """.formatted(slash(temp.resolve("args.txt")), slash(temp.resolve("body-copy.txt")), printed, exitCode));
        return source;
    }

    private static String slash(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String template(Path stub) {
        return "java %s {base} {head} {title} {bodyFile}".formatted(slash(stub));
    }

    @Test
    void theCommandRunsOnceWithEngineMadeValuesAndTheFirstLinkIsRecorded() throws Exception {
        Path stub = stub("Created: https://host.example/org/shop/pull/7 and https://other.example/x", 0);
        given(session(SessionState.DONE, 1, false, "release/1.2"));

        var outcome = publisher(PublishMode.PULL_REQUEST, template(stub)).afterSession(ID).orElseThrow();

        assertThat(outcome.publishState()).isEqualTo("pushed");
        assertThat(outcome.pullRequestState()).isEqualTo("created");
        assertThat(outcome.pullRequestLink()).isEqualTo("https://host.example/org/shop/pull/7");
        verify(sessions).setPullRequest(ID, "created", "https://host.example/org/shop/pull/7");
        List<String> args = Files.readAllLines(temp.resolve("args.txt"));
        assertThat(args).hasSize(4);
        assertThat(args.get(0)).isEqualTo("release/1.2");
        assertThat(args.get(1)).isEqualTo(BRANCH);
        assertThat(args.get(2)).isEqualTo("Security fixes from automated session 0a1b2c3d");
        assertThat(Files.readString(temp.resolve("body-copy.txt"))).contains("automated fix session").doesNotContain("WARNING");
    }

    @Test
    void aFailingCommandKeepsTheBranchPushedAndRecordsTheFailure() throws Exception {
        Path stub = stub("boom", 3);
        given(session(SessionState.DONE, 1, false, "main"));

        var outcome = publisher(PublishMode.PULL_REQUEST, template(stub)).afterSession(ID).orElseThrow();

        assertThat(outcome.publishState()).isEqualTo("pushed");
        assertThat(outcome.pullRequestState()).isEqualTo("failed");
        assertThat(remoteBranches()).contains(BRANCH);
        verify(sessions).setPullRequest(ID, "failed", null);
        assertThat(events()).anyMatch(e -> e.contains("pull request command failed") && e.contains("code 3"));
    }

    @Test
    void aMissingProgramAnUnknownPlaceholderOrNoTemplateFallBackToPushAndSayWhy() throws Exception {
        given(session(SessionState.DONE, 1, false, "main"));
        var missing = publisher(PublishMode.PULL_REQUEST, "no-such-program-xyz {base}").afterSession(ID).orElseThrow();
        assertThat(missing.pullRequestState()).isEqualTo("skipped");
        assertThat(missing.message()).contains("no-such-program-xyz").contains("not on the PATH");
        assertThat(remoteBranches()).contains(BRANCH);

        // a template that cannot be started because of an unknown placeholder is refused at startup of the engine, and a repository's
        // own template can still carry one: it is reported at run time and the branch stays pushed
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/publish.json"), "{\"mode\":\"pull-request\",\"pullRequestCommand\":\"java {nope}\"}");
        var unknown = publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow();
        assertThat(unknown.pullRequestState()).isEqualTo("skipped");
        assertThat(unknown.message()).contains("{nope}");

        Files.writeString(repo.resolve(".appmod/publish.json"), "{\"mode\":\"pull-request\"}");
        var noTemplate = publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow();
        assertThat(noTemplate.pullRequestState()).isEqualTo("skipped");
        assertThat(noTemplate.message()).contains("no pull-request command").contains(BRANCH).contains("main");

        Files.delete(repo.resolve(".appmod/publish.json"));
        var none = publisher(PublishMode.PUSH, null).afterSession(ID).orElseThrow();
        assertThat(none.pullRequestState()).isNull();
    }

    @Test
    void textFromAReportOrTheTitleCannotBecomeAnArgumentOrACommand() {
        var parsed = PullRequestCommand.parse("gh pr create --title {title} --body-file {bodyFile}");
        List<String> filled = PullRequestCommand.fill(parsed.arguments(), java.util.Map.of("title", "x; rm -rf / && {base} --force",
                "bodyFile", "C:/a b/body.md", "base", "SHOULD-NOT-BE-EXPANDED"));
        assertThat(filled).containsExactly("gh", "pr", "create", "--title", "x; rm -rf / && {base} --force", "--body-file", "C:/a b/body.md");
    }

    @Test
    void theTemplateIsSplitByQuotesAndValidated() {
        assertThat(PullRequestCommand.parse("glab mr create --title \"Fix {head}\" -t 'a b'").arguments())
                .containsExactly("glab", "mr", "create", "--title", "Fix {head}", "-t", "a b");
        assertThat(PullRequestCommand.parse("").ok()).isFalse();
        assertThat(PullRequestCommand.parse("gh \"unclosed").problem()).contains("quote");
        assertThat(PullRequestCommand.parse("{base} x").problem()).contains("placeholder");
        assertThat(PullRequestCommand.parse("gh {bogus}").problem()).contains("{bogus}");
    }

    @Test
    void aLocalChangesSessionWarnsInTheDescriptionAndWaitsForConfirmation() throws Exception {
        Path stub = stub("https://host.example/pr/9", 0);
        given(session(SessionState.DONE, 1, true, null));

        var waiting = publisher(PublishMode.PULL_REQUEST, template(stub)).afterSession(ID).orElseThrow();
        assertThat(waiting.publishState()).isEqualTo("awaiting_confirmation");
        assertThat(remoteBranches()).doesNotContain(BRANCH);
        verify(sessions).setPublishState(ID, "awaiting_confirmation");
        assertThat(events()).anyMatch(e -> e.contains("publish-branch " + ID + " --yes"));

        var confirmed = publisher(PublishMode.PULL_REQUEST, template(stub)).confirmed(session(SessionState.DONE, 1, true, null));
        assertThat(confirmed.publishState()).isEqualTo("pushed");
        assertThat(confirmed.pullRequestState()).isEqualTo("created");
        assertThat(remoteBranches()).contains(BRANCH);
        assertThat(Files.readString(temp.resolve("body-copy.txt"))).contains("WARNING").contains("uncommitted");
        assertThat(File.separator).isNotNull();
    }

    @Test
    void refusalExplainsWhyASessionCannotBePublishedByHand() {
        BranchPublisher p = publisher(PublishMode.PUSH, null);
        assertThat(p.refusal(null)).contains("no such session");
        assertThat(p.refusal(session(SessionState.IN_PROGRESS, 1, false, null))).contains("DONE or PARTIAL");
        assertThat(p.refusal(session(SessionState.DONE, 0, false, null))).contains("no commits");
        assertThat(p.refusal(session(SessionState.DONE, 2, false, null))).isNull();
    }
}
