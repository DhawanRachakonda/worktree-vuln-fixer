package dev.subscriber.worktree;

import static dev.subscriber.worktree.WorktreeServiceTest.git;
import static dev.subscriber.worktree.WorktreeServiceTest.initRepository;
import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.worktree.GitPreflight.Check;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine's {@link GitPreflight} and the kit installer's copy ({@code kit/install/GitSetup.java}) are deliberate copies (AGENTS.md,
 * "Deliberate copies"). This test runs both on the same repositories and compares every check: its name, status, detail and the
 * consequence text, so that neither can change without the other.
 */
class GitPreflightParityTest {

    private static final Path KIT_COPY = Path.of("..", "kit", "install", "GitSetup.java").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    Path root;
    Path repo;
    GitPreflight engine;

    @BeforeEach
    void setUp() throws Exception {
        root = temp.toRealPath();
        repo = initRepository(root.resolve("shop"));
        git(repo, "branch", "-M", "main");
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()),
                List.of(), SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null));
        engine = new GitPreflight(props, new GitRunner(root.resolve("no-hooks"), 20), null);
    }

    private record Line(String check, String status, String detail, String consequence) {

        /** git writes how long a failed connection took; that number is not part of what is compared. */
        Line steady() {
            return new Line(check, status, detail.replaceAll("after [0-9]+ ms", "after N ms"), consequence);
        }
    }

    private List<Line> kit(String branch) throws Exception {
        assertThat(KIT_COPY).as("the kit's copy of the checks").isRegularFile();
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString(), KIT_COPY.toString(),
                "--check", repo.toString()));
        if (branch != null) {
            command.addAll(List.of("--branch", branch));
        }
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
        List<Line> lines = new ArrayList<>();
        for (String line : out.lines().filter(l -> !l.isBlank()).toList()) {
            String[] parts = line.split("\t", -1);
            assertThat(parts).as(line).hasSize(4);
            lines.add(new Line(parts[0], parts[1], parts[2], parts[3]).steady());
        }
        return lines;
    }

    private List<Line> engineLines(String branch) {
        return engine.run(repo, branch).stream().map((Check c) -> new Line(c.check(), c.status().name(), c.detail(), c.consequence()).steady()).toList();
    }

    private void same(String branch) throws Exception {
        List<Line> fromEngine = engineLines(branch);
        List<Line> fromKit = kit(branch);
        assertThat(fromKit).as("the kit copy and the engine must give the same checks").isEqualTo(fromEngine);
        assertThat(fromEngine).isNotEmpty();
    }

    private Path bareOrigin() throws Exception {
        Path bare = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "-q", "origin", "main");
        return bare;
    }

    private void saved(String json) throws IOException {
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/publish.json"), json);
    }

    @Test
    void aRepositoryWithoutARemote() throws Exception {
        same(null);
    }

    @Test
    void inModeNoneBothSkipTheRemoteEvenWhenOneIsConfigured() throws Exception {
        bareOrigin();
        same(null);
        assertThat(kit(null).stream().map(Line::check)).contains("remote checks").doesNotContain("remote origin");
    }

    @Test
    void aRepositoryWithoutARemoteInPushMode() throws Exception {
        saved("{\"mode\":\"push\"}");
        same(null);
    }

    @Test
    void aLocalBareRemoteThatIsTheRemoteDefault() throws Exception {
        saved("{\"mode\":\"push\"}");
        Path bare = bareOrigin();
        git(repo, "branch", "develop");
        git(repo, "push", "-q", "origin", "develop");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/develop");
        git(repo, "fetch", "-q", "origin");
        git(repo, "remote", "set-head", "origin", "--auto");
        same(null);
    }

    @Test
    void anUnreachableRemoteWithCredentialsInItsUrl() throws Exception {
        saved("{\"mode\":\"push\"}");
        git(repo, "remote", "add", "origin", "http://alice:s3cr3t-token@127.0.0.1:1/shop.git");
        same(null);
        assertThat(kit(null).toString()).doesNotContain("s3cr3t-token");
    }

    @Test
    void aGivenBranchThatExistsAndOneThatDoesNot() throws Exception {
        saved("{\"mode\":\"push\"}");
        bareOrigin();
        same("main");
        same("nope");
        same("a b");
        same("--upload-pack=x");
    }

    @Test
    void savedSettingsOfEveryKind() throws Exception {
        for (String json : List.of("{\"sourceBranch\":\"main\"}", "{\"sourceBranch\":\"gone\"}", "{\"mode\":\"push\"}",
                "{\"sourceBranch\":\"main\",\"mode\":\"none\",\"pullRequestCommand\":\"gh pr create\"}", "{nope", "[1]", "{\"x\":1}",
                "{\"sourceBranch\":5}", "{\"sourceBranch\":\"-rf\"}", "{\"mode\":\"force\"}", "{}")) {
            saved(json);
            List<Line> fromKit = kit(null);
            List<Line> fromEngine = engineLines(null);
            assertThat(fromKit).as(json).isEqualTo(fromEngine);
        }
    }

    @Test
    void theBranchNameRuleIsTheSame() throws Exception {
        for (String name : List.of("main", "release/1.2", "a..b", "a//b", "-x", "a.lock", "a b", "x;rm", "$(x)", ".hidden", "a/.b", "a/", "/a", "v1.0.0")) {
            git(repo, "branch", "-f", "scratch-" + Math.abs(name.hashCode()) % 1000, "main");
            assertThat(kit(name).stream().filter(l -> l.check().equals("source branch")).toList())
                    .as(name).isEqualTo(engineLines(name).stream().filter(l -> l.check().equals("source branch")).toList());
        }
    }
}
