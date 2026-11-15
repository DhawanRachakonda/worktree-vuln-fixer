package dev.subscriber.nats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.PublishMode;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeChecker;
import dev.subscriber.intake.IntakeService;
import dev.subscriber.lifecycle.SessionDispatcher;
import dev.subscriber.lifecycle.SessionLifecycle;
import dev.subscriber.lifecycle.SessionQueued;
import dev.subscriber.runner.RepositoryProbe.RepositoryState;
import dev.subscriber.runner.RunningSession;
import dev.subscriber.runner.SessionLauncher;
import dev.subscriber.session.PromptWriter;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import dev.subscriber.worktree.BranchPublisher;
import dev.subscriber.worktree.GitPreflight;
import dev.subscriber.worktree.SourceBranchPreview;
import dev.subscriber.worktree.WorktreeService;
import io.nats.client.Connection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The whole path with nothing simulated except the coding agent: the console's {@code publish} sends a report with a source branch
 * through a real NATS server (a Docker container), the engine's own subscriber records it, the dispatcher makes a real git worktree
 * from the chosen branch, a stand-in agent commits in it, the session ends, and the engine pushes the branch to a bare repository and
 * runs a pull-request command (a stub program that records its arguments). Opt-in like the other live tests: {@code -Pnats-tests}.
 */
@JdbcTest(properties = "source-branch-e2e.test=true")
@Import(FixSessionRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SourceBranchEndToEndTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final String LOCATION = ".appmod/security-audit/issues.csv";
    private static final String SUBJECT = "securityaudit.issuefixesAgent";

    @TempDir
    Path temp;

    @Autowired
    FixSessionRepository sessions;

    @Autowired
    JdbcClient jdbc;

    private NatsTestServer nats;
    private NatsMessageSource source;
    private SessionDispatcher dispatcher;

    Path root;
    Path repo;
    Path bare;
    ReportPublisher publisher;
    BranchPublisher branchPublisher;
    String mainTip;
    String developTip;
    String releaseTip;

    @AfterEach
    void stop() throws Exception {
        if (source != null) {
            source.stop();
        }
        if (dispatcher != null) {
            dispatcher.stop();
        }
        if (nats != null) {
            nats.close();
        }
        jdbc.sql("DELETE FROM fix_session_event").update();
        jdbc.sql("DELETE FROM fix_session").update();
    }

    /** A stand-in for the coding agent: after a short while it commits one fix in its working directory and reports success. */
    private final class StandInAgent implements SessionLauncher {

        SessionFolders folders;

        @Override
        public RunningSession launch(String sessionId, Path workingDirectory, String agent) {
            CompletableFuture<Void> ended = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try {
                    // the engine records the start before it looks for a report, which must be newer than that
                    Thread.sleep(1500);
                    Files.writeString(workingDirectory.resolve("fix.txt"), "fixed by the stand-in agent\n");
                    git(workingDirectory, "add", "fix.txt");
                    git(workingDirectory, "commit", "-q", "-m", "fix: one issue");
                    Files.createDirectories(workingDirectory.resolve(".appmod"));
                    Files.writeString(workingDirectory.resolve(".appmod/result.json"), "{\"status\": \"succeeded\"}");
                    Files.writeString(folders.create(sessionId).resolve("exit.json"),
                            "{\"exitCode\": 0, \"endedAt\": \"" + Instant.now() + "\", \"headEnd\": null}");
                    ended.complete(null);
                } catch (Exception e) {
                    ended.completeExceptionally(e);
                }
            });
            return new RunningSession() {
                @Override
                public long pid() {
                    return 4242;
                }

                @Override
                public Instant pidStartedAt() {
                    return Instant.now();
                }

                @Override
                public CompletableFuture<Void> ended() {
                    return ended;
                }
            };
        }
    }

    /** Runs git in a folder (with an identity, no signing) and returns what it printed; a failure fails the test. */
    private static String git(Path folder, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out.strip();
    }

    private Path stub() throws IOException {
        Path source = temp.resolve("PullRequestStub.java");
        Files.writeString(source, """
                import java.nio.file.*;
                import java.util.*;

                public class PullRequestStub {
                    public static void main(String[] args) throws Exception {
                        Files.write(Path.of("%s"), Arrays.asList(args));
                        System.out.println("https://host.example/org/shop/pull/42");
                    }
                }
                """.formatted(temp.resolve("pr-args.txt").toString().replace('\\', '/')));
        return source;
    }

    /** The files that make the repository look like a project of that kind; the engine only needs git, so no build tool is run. */
    private static java.util.Map<String, String> sampleFiles(String project) {
        String nl = System.lineSeparator();
        return switch (project) {
            case "node-blog" -> java.util.Map.of("package.json", "{\"name\":\"node-blog\",\"dependencies\":{\"express\":\"4.17.1\"}}" + nl,
                    "index.js", "const express = require('express');" + nl);
            case "python-api" -> java.util.Map.of("requirements.txt", "flask==2.0.1" + nl, "app.py", "from flask import Flask" + nl);
            default -> java.util.Map.of("pom.xml", "<project><artifactId>spring-shop</artifactId></project>" + nl,
                    "src/main/java/demo/ShopApplication.java", "package demo;" + nl + "public class ShopApplication {}" + nl);
        };
    }

    private void startEverything() throws Exception {
        startEverything("spring-shop");
    }

    private void startEverything(String project) throws Exception {
        root = temp.toRealPath();
        repo = Files.createDirectories(root.resolve("shop"));
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        for (var file : sampleFiles(project).entrySet()) {
            Files.createDirectories(repo.resolve(file.getKey()).getParent());
            Files.writeString(repo.resolve(file.getKey()), file.getValue());
        }
        // appmod init ignores the agent's folder, and a snapshot of local changes has to work in such a repository
        Files.writeString(repo.resolve(".gitignore"), ".appmod/" + System.lineSeparator());
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "first");
        git(repo, "branch", "-M", "main");
        mainTip = git(repo, "rev-parse", "HEAD");
        git(repo, "branch", "release/1.0");
        releaseTip = git(repo, "rev-parse", "release/1.0");
        git(repo, "checkout", "-q", "-b", "develop");
        Files.writeString(repo.resolve("dev.txt"), "dev\n");
        git(repo, "add", "dev.txt");
        git(repo, "commit", "-q", "-m", "develop work");
        developTip = git(repo, "rev-parse", "HEAD");
        bare = root.resolve("origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        git(repo, "push", "-q", "origin", "main", "develop", "release/1.0");
        git(bare, "symbolic-ref", "HEAD", "refs/heads/develop");
        git(repo, "fetch", "-q", "origin");
        git(repo, "remote", "set-head", "origin", "--auto");
        // the person is on a feature branch and has not committed everything
        git(repo, "checkout", "-q", "-b", "feature/wip");
        Files.writeString(repo.resolve("x.txt"), "work in progress\n");
        Files.createDirectories(repo.resolve(".appmod/security-audit"));
        Files.writeString(repo.resolve(LOCATION), "file,line,title\nx,1,y\n");

        nats = NatsTestServer.start(root);
        // the program is named by its absolute path, so this test does not depend on what is on the PATH
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString().replace('\\', '/');
        String command = "\"%s\" \"%s\" {base} {head} {title} {bodyFile}".formatted(java, stub().toString().replace('\\', '/'));
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()),
                List.of(new Subject(SUBJECT, "Security audit", "security-fixer", null, LOCATION, SubscriberProperties.Isolation.WORKTREE)),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://127.0.0.1:" + nats.port(), null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.WORKTREE, SubscriberProperties.AgentTool.COPILOT, new SubscriberProperties.Claude("claude"),
                new SubscriberProperties.Publish(PublishMode.PULL_REQUEST, command));

        SessionFolders folders = new SessionFolders(props);
        WorktreeService worktrees = new WorktreeService(props);
        branchPublisher = new BranchPublisher(props, sessions, folders, Clock.systemUTC());
        SessionLifecycle lifecycle = new SessionLifecycle(sessions, folders, Clock.systemUTC(), worktrees);
        lifecycle.setPublisher(branchPublisher);
        StandInAgent agent = new StandInAgent();
        agent.folders = folders;
        dispatcher = new SessionDispatcher(props, sessions, lifecycle, agent, repository -> new RepositoryState("head", true), worktrees,
                new PromptWriter(folders, props));
        IntakeChecker checker = new IntakeChecker(props, sessions);
        IntakeService intake = new IntakeService(props, checker, sessions, folders, new PromptWriter(folders, props), event -> {
            if (event instanceof SessionQueued) {
                dispatcher.kick();
            }
        }, Clock.systemUTC());
        source = new NatsMessageSource(props, intake);
        source.start();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(source.status()).isEqualTo(Connection.Status.CONNECTED));
        Thread.sleep(500);
        publisher = new ReportPublisher(props, checker, new SourceBranchPreview(props), new GitPreflight(props));
    }

    private FixSession onlySession() {
        await().atMost(Duration.ofSeconds(20)).until(() -> sessions.count(null) == 1);
        return sessions.list(null, 10, 0).getFirst();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"spring-shop", "node-blog", "python-api"})
    void aReportPublishedWithASourceBranchEndsAsAPushedBranchAndAPullRequestAgainstThatBranch(String project) throws Exception {
        startEverything(project);
        String before = git(repo, "status", "--porcelain", "-uall");

        var result = publisher.publish(null, repo.resolve(LOCATION).toString(), "release/1.0", false);
        assertThat(result.published()).as(result.message()).isTrue();
        assertThat(result.message()).contains("branch release/1.0").contains("given");

        String id = onlySession().id();
        await().atMost(WAIT).untilAsserted(() -> {
            FixSession s = sessions.find(id).orElseThrow();
            assertThat(s.state()).isEqualTo(SessionState.DONE);
            assertThat(s.pullRequestState()).isEqualTo("created");
        });
        FixSession s = sessions.find(id).orElseThrow();
        String branch = s.workBranch();

        // the worktree was based on the given branch, not on the feature branch the person was on, and not on develop (the default)
        assertThat(s.sourceBranch()).isEqualTo("release/1.0");
        assertThat(s.sourceChoice()).isEqualTo("publish");
        assertThat(s.baseCommit()).isEqualTo(releaseTip);
        assertThat(s.localChanges()).isFalse();
        assertThat(s.fixCommits()).isEqualTo(1);
        // pushed, with the agent's commit on top of the release branch
        assertThat(s.publishState()).isEqualTo("pushed");
        assertThat(git(bare, "branch", "--list", branch)).contains(branch);
        assertThat(git(bare, "log", "-1", "--format=%s", branch)).isEqualTo("fix: one issue");
        assertThat(git(bare, "rev-parse", branch + "~1")).isEqualTo(releaseTip);
        // the pull-request command ran once, against the source branch, from the pushed branch
        assertThat(s.pullRequestLink()).isEqualTo("https://host.example/org/shop/pull/42");
        List<String> args = Files.readAllLines(temp.resolve("pr-args.txt"));
        assertThat(args).hasSize(4);
        assertThat(args.get(0)).isEqualTo("release/1.0");
        assertThat(args.get(1)).isEqualTo(branch);
        assertThat(sessions.events(id)).extracting(SessionEvent::detail).anyMatch(d -> d.contains("pushed " + branch))
                .anyMatch(d -> d.contains("pull request created"));
        // the person's checkout is exactly as it was
        assertThat(git(repo, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/wip");
        assertThat(git(repo, "status", "--porcelain", "-uall")).isEqualTo(before);
    }

    @Test
    void aSessionThatIncludedLocalChangesIsNotPushedUntilItIsConfirmed() throws Exception {
        startEverything();

        var result = publisher.publish(null, repo.resolve(LOCATION).toString(), null, true);
        assertThat(result.published()).as(result.message()).isTrue();
        assertThat(result.message()).contains("second confirmation");

        String id = onlySession().id();
        await().atMost(WAIT).untilAsserted(() -> {
            FixSession s = sessions.find(id).orElseThrow();
            assertThat(s.state()).isEqualTo(SessionState.DONE);
            assertThat(s.publishState()).isEqualTo("awaiting_confirmation");
        });
        FixSession s = sessions.find(id).orElseThrow();
        assertThat(s.localChanges()).isTrue();
        assertThat(s.sourceBranch()).isEqualTo("feature/wip");
        assertThat(git(bare, "branch", "--list", s.workBranch())).as("nothing was pushed").isEmpty();
        assertThat(temp.resolve("pr-args.txt")).doesNotExist();
        assertThat(git(Path.of(s.workPath()), "show", "--stat", "--format=%s", "HEAD~1")).contains("Uncommitted local work").contains("x.txt");

        var confirmed = branchPublisher.confirmed(s);

        assertThat(confirmed.publishState()).isEqualTo("pushed");
        assertThat(confirmed.pullRequestState()).isEqualTo("created");
        assertThat(git(bare, "branch", "--list", s.workBranch())).contains(s.workBranch());
        assertThat(Files.readString(temp.resolve("pr-args.txt"))).contains(s.workBranch());
        assertThat(Files.readAllLines(Path.of(s.workPath()).getParent().resolve("data/sessions").resolve(id).resolve("pull-request.md")))
                .anyMatch(line -> line.contains("WARNING"));
    }

    @Test
    void aBranchThatDoesNotExistIsRefusedAtThePublishAndNothingIsQueued() throws Exception {
        startEverything();

        var result = publisher.publish(null, repo.resolve(LOCATION).toString(), "release/9.9", false);

        assertThat(result.published()).isFalse();
        assertThat(result.message()).contains("release/9.9").contains("does not exist");
        Thread.sleep(1500);
        assertThat(sessions.count(null)).isZero();
    }
}
