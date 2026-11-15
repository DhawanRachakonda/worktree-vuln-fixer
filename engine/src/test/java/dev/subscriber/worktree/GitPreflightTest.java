package dev.subscriber.worktree;

import static dev.subscriber.worktree.WorktreeServiceTest.git;
import static dev.subscriber.worktree.WorktreeServiceTest.initRepository;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.PublishMode;
import dev.subscriber.console.CommandFinder;
import dev.subscriber.worktree.GitPreflight.Check;
import dev.subscriber.worktree.GitPreflight.Status;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The git readiness checks against real repositories: a local bare remote, no remote, an unreachable one, a hanging one, a refusing one. */
class GitPreflightTest {

    @TempDir
    Path temp;

    Path root;
    Path repo;

    @BeforeEach
    void setUp() throws Exception {
        root = temp.toRealPath();
        repo = initRepository(root.resolve("shop"));
        git(repo, "branch", "-M", "main");
    }

    /** Git with no system or global configuration, so no credential helper or askpass of this machine can ever open a dialog. */
    private static final class IsolatedGit extends GitRunner {
        private final Path emptyConfig;

        IsolatedGit(Path noHooks, long seconds, Path emptyConfig) {
            super(noHooks, seconds);
            this.emptyConfig = emptyConfig;
        }

        @Override
        Result run(Path workingDirectory, java.util.Map<String, String> environment, String... args) throws java.io.IOException {
            java.util.Map<String, String> env = new java.util.HashMap<>(environment);
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_CONFIG_GLOBAL", emptyConfig.toString());
            return super.run(workingDirectory, env, args);
        }
    }

    private GitPreflight preflight(PublishMode mode, String command, long gitSeconds) {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()),
                List.of(), SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.WORKTREE, SubscriberProperties.AgentTool.COPILOT, new SubscriberProperties.Claude("claude"),
                new SubscriberProperties.Publish(mode, command));
        String javaDir = Path.of(ProcessHandle.current().info().command().orElseThrow()).getParent().toString();
        return new GitPreflight(props, new IsolatedGit(root.resolve("no-hooks"), gitSeconds, emptyConfig()),
                new CommandFinder(javaDir, System.getProperty("os.name").toLowerCase().contains("win"), null));
    }

    private Path emptyConfig() {
        try {
            return Files.writeString(root.resolve("empty-gitconfig"), "");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Check named(List<Check> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow(() -> new AssertionError(name + " in " + checks));
    }

    private Path bareOrigin() throws Exception {
        Path bare = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "-q", "origin", "main");
        return bare;
    }

    @Test
    void aLocalBareRemoteMakesEveryCheckPassAndNothingIsPushed() throws Exception {
        Path bare = bareOrigin();
        List<Check> checks = preflight(PublishMode.PUSH, null, 30).run(repo, null);

        assertThat(GitPreflight.failed(checks)).as(checks.toString()).isFalse();
        assertThat(named(checks, "git").detail()).contains("git version");
        assertThat(named(checks, "remote origin").status()).isEqualTo(Status.OK);
        assertThat(named(checks, "remote reachable").status()).isEqualTo(Status.OK);
        assertThat(named(checks, "push allowed").detail()).startsWith("likely");
        assertThat(named(checks, "source branch").detail()).contains("main");
        assertThat(git(bare, "branch", "--list")).doesNotContain("appmod/preflight-probe");
    }

    @Test
    void inModeNoneTheRemoteIsNotLookedAtAndTheReportSaysWhyButAMissingRemoteFailsWhenPushIsOn() throws Exception {
        git(repo, "remote", "add", "origin", "http://127.0.0.1:1/shop.git");
        long start = System.nanoTime();
        List<Check> none = preflight(PublishMode.NONE, null, 30).run(repo, null);
        assertThat(none.stream().map(Check::check)).doesNotContain("remote origin", "remote reachable", "push allowed");
        assertThat(named(none, "remote checks").detail()).contains("skipped").contains("publish mode is none");
        assertThat(GitPreflight.failed(none)).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000_000L).as("no network was used").isLessThan(20);
        git(repo, "remote", "remove", "origin");

        List<Check> push = preflight(PublishMode.PUSH, null, 30).run(repo, null);
        assertThat(named(push, "remote origin").status()).isEqualTo(Status.FAIL);
        assertThat(named(push, "remote origin").consequence()).contains("stays a local branch");
        assertThat(push.stream().map(Check::check)).doesNotContain("remote reachable");
    }

    @Test
    void anUnreachableRemoteIsReportedWithoutItsCredentials() throws Exception {
        git(repo, "remote", "add", "origin", "http://alice:s3cr3t-token@127.0.0.1:1/shop.git");
        List<Check> checks = preflight(PublishMode.PUSH, null, 30).run(repo, null);

        assertThat(named(checks, "remote reachable").status()).isEqualTo(Status.FAIL);
        assertThat(named(checks, "push allowed").status()).isEqualTo(Status.FAIL);
        assertThat(checks.stream().map(Check::line).toList()).noneMatch(l -> l.contains("s3cr3t-token") || l.contains("alice"));
        assertThat(named(checks, "remote origin").detail()).contains("***@");
    }

    @Test
    void aRemoteThatNeverAnswersHitsTheTimeLimit() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread accepting = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        silent.accept();
                    }
                } catch (java.io.IOException _) {
                    // closed at the end of the test
                }
            });
            git(repo, "remote", "add", "origin", "http://127.0.0.1:%d/shop.git".formatted(silent.getLocalPort()));
            long start = System.nanoTime();

            List<Check> checks = preflight(PublishMode.PUSH, null, 3).run(repo, null);

            assertThat(named(checks, "remote reachable").status()).isEqualTo(Status.FAIL);
            assertThat(named(checks, "remote reachable").detail()).contains("did not finish");
            assertThat((System.nanoTime() - start) / 1_000_000_000L).as("seconds").isLessThan(40);
            accepting.interrupt();
        }
    }

    @Test
    void aHostThatRefusesTheCredentialIsAFailureNamingTheReason() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            git(repo, "remote", "add", "origin", "http://127.0.0.1:%d/shop.git".formatted(server.getAddress().getPort()));
            List<Check> checks = preflight(PublishMode.PUSH, null, 30).run(repo, null);

            Check reachable = named(checks, "remote reachable");
            assertThat(reachable.status()).isEqualTo(Status.FAIL);
            assertThat(reachable.detail().toLowerCase()).containsAnyOf("authentication", "username", "401", "could not read");
            assertThat(reachable.consequence()).contains("credentials");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aSourceBranchThatDoesNotExistOrAnInvalidSettingIsAFailureEvenWithoutAPublishMode() throws Exception {
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/publish.json"), "{\"sourceBranch\":\"gone\"}");
        List<Check> missing = preflight(PublishMode.NONE, null, 30).run(repo, null);
        assertThat(named(missing, "source branch").status()).isEqualTo(Status.FAIL);
        assertThat(named(missing, "source branch").detail()).contains("gone").contains("saved");

        Files.writeString(repo.resolve(".appmod/publish.json"), "{nope");
        List<Check> broken = preflight(PublishMode.NONE, null, 30).run(repo, null);
        assertThat(named(broken, "saved settings").status()).isEqualTo(Status.FAIL);
        assertThat(named(broken, "saved settings").detail()).contains(".appmod/publish.json");
    }

    @Test
    void theGivenBranchIsCheckedInPlaceOfTheSavedOne() throws Exception {
        assertThat(named(preflight(PublishMode.NONE, null, 30).run(repo, "main"), "source branch").detail()).contains("main").contains("given");
        assertThat(named(preflight(PublishMode.NONE, null, 30).run(repo, "nope"), "source branch").status()).isEqualTo(Status.FAIL);
    }

    @Test
    void thePullRequestProgramMustBeOnThePathInPullRequestMode() throws Exception {
        bareOrigin();
        List<Check> present = preflight(PublishMode.PULL_REQUEST, "java -version {base}", 30).run(repo, null);
        assertThat(named(present, "pull-request command").status()).isEqualTo(Status.OK);
        assertThat(named(present, "pull-request command").detail()).contains("will run");

        List<Check> missing = preflight(PublishMode.PULL_REQUEST, "no-such-program-xyz {base}", 30).run(repo, null);
        assertThat(named(missing, "pull-request command").status()).isEqualTo(Status.FAIL);
        assertThat(named(missing, "pull-request command").detail()).contains("no-such-program-xyz").contains("not on the PATH");

        assertThat(preflight(PublishMode.PUSH, null, 30).run(repo, null).stream().map(Check::check)).doesNotContain("pull-request command");
    }

    @Test
    void theCheapSubsetNeedsNoNetwork() throws Exception {
        git(repo, "remote", "add", "origin", "http://127.0.0.1:1/shop.git");
        long start = System.nanoTime();
        List<Check> checks = preflight(PublishMode.PUSH, null, 30).cheap(repo, null);
        assertThat(checks.stream().map(Check::check)).containsExactly("git", "saved settings", "source branch");
        assertThat(GitPreflight.failed(checks)).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000_000L).isLessThan(20);
    }
}
