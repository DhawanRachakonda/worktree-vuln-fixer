package dev.subscriber.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.subscriber.SubscriberEngineApplication;
import dev.subscriber.session.SessionFolders;
import dev.subscriber.store.FixSession;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionEvent;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Restarts the whole application on a file database that a previous run left in each of the situations of the spec. The database is
 * prepared directly (no application running), then the application is started on it, with a fake launcher so no real agent runs.
 */
class RecoveryTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path data;

    @TempDir
    Path root;

    private final List<Process> processes = new ArrayList<>();
    private ConfigurableApplicationContext context;

    @Configuration(proxyBeanMethods = false)
    static class TestLauncher {
        /** Replaces the production selector bean of the same name, so there is still exactly one primary launcher. */
        @Bean("sessionLauncher")
        @Primary
        FakeLauncher fakeLauncher() {
            return new FakeLauncher();
        }
    }

    @AfterEach
    void cleanUp() {
        if (context != null) {
            context.close();
        }
        processes.forEach(Process::destroyForcibly);
    }

    private static String dir(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    /** Prepares the database file as a previous run left it: migrated, with the rows the test needs, and not open by anyone. */
    private void seed(java.util.function.Consumer<FixSessionRepository> rows) {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:file:" + dir(data) + "/subscriber", "sa", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        rows.accept(new FixSessionRepository(JdbcClient.create(ds)));
    }

    /** These scenarios are about recovery, not worktrees: sessions work in the checkout, as with isolation none. */
    private ConfigurableApplicationContext start() {
        return start("none");
    }

    private ConfigurableApplicationContext start(String isolation) {
        context = new SpringApplicationBuilder(SubscriberEngineApplication.class, TestLauncher.class)
                .web(WebApplicationType.NONE)
                .run("--subscriber.data-dir=" + dir(data), "--subscriber.allowed-roots=" + dir(root), "--subscriber.copilot.permission-mode=allow-all-tools",
                        "--subscriber.max-parallel=1", "--subscriber.isolation=" + isolation, "--spring.main.banner-mode=off",
                        "--spring.main.allow-bean-definition-overriding=true");
        return context;
    }

    private Path repo(String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    private static String queue(FixSessionRepository repo, Path repository, Instant received) {
        String id = UUID.randomUUID().toString();
        repo.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-" + id,
                repository.toString(), "security-fixer", "copilot", received, null));
        return id;
    }

    /** A process that is still running, standing in for the window. */
    private Process longRunning() throws IOException {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("ping", "-n", "300", "127.0.0.1") : List.of("sleep", "300");
        Process p = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        processes.add(p);
        return p;
    }

    /** A process that has already ended, so its id and start time identify nothing alive. */
    private Process finished() throws Exception {
        List<String> command = System.getProperty("os.name").toLowerCase().contains("win")
                ? List.of("cmd.exe", "/c", "exit", "0") : List.of("sh", "-c", "exit 0");
        Process p = new ProcessBuilder(command).start();
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return p;
    }

    private void leaveEndRecords(String sessionId, Path repository, String reportStatus) throws IOException {
        Path folder = new SessionFolders(new dev.subscriber.config.SubscriberProperties(dir(data), List.of(dir(root)), List.of(),
                dev.subscriber.config.SubscriberProperties.Runner.HEADLESS, 1,
                new dev.subscriber.config.SubscriberProperties.Copilot("copilot",
                        dev.subscriber.config.SubscriberProperties.PermissionMode.ASK),
                new dev.subscriber.config.SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new dev.subscriber.config.SubscriberProperties.Access(null))).create(sessionId);
        Files.writeString(folder.resolve("exit.json"), "{\"exitCode\": 0, \"endedAt\": \"" + Instant.now() + "\", \"headEnd\": \"endsha\"}");
        Path report = repository.resolve(".appmod/result.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"status\": \"" + reportStatus + "\"}");
    }

    private FixSessionRepository repository() {
        return context.getBean(FixSessionRepository.class);
    }

    private SessionState state(String id) {
        return repository().find(id).orElseThrow().state();
    }

    @Test
    void queuedSessionsSurviveARestartAndRunInTheirOriginalOrder() throws Exception {
        List<String> ids = new ArrayList<>();
        List<Path> repos = new ArrayList<>();
        seed(r -> {
            try {
                for (String name : List.of("repo-a", "repo-b", "repo-c")) {
                    repos.add(repo(name));
                    ids.add(queue(r, repos.get(repos.size() - 1), Instant.parse("2026-11-10T10:00:00Z")));
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        start();
        FakeLauncher launcher = context.getBean(FakeLauncher.class);
        for (int i = 0; i < 3; i++) {
            int index = i;
            await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactlyElementsOf(ids.subList(0, index + 1)));
            leaveEndRecords(ids.get(i), repos.get(i), "succeeded");
            launcher.ends.get(ids.get(i)).complete(null);
        }
        await().atMost(WAIT).untilAsserted(() -> assertThat(state(ids.get(2))).isEqualTo(SessionState.DONE));
        assertThat(ids).allSatisfy(id -> assertThat(state(id)).isEqualTo(SessionState.DONE));
    }

    @Test
    void aWindowThatIsStillOpenKeepsItsSessionRunningAndItsRepositoryBusyUntilItEnds() throws Exception {
        Path shared = repo("shared");
        Process window = longRunning();
        String running = UUID.randomUUID().toString();
        String waiting = UUID.randomUUID().toString();
        seed(r -> {
            r.insert(new NewSession(running, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    shared.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            r.markStarted(running, "window", Instant.parse("2026-11-10T09:01:00Z"), window.pid(),
                    window.info().startInstant().orElseThrow(), "startsha", false);
            r.insert(new NewSession(waiting, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-2",
                    shared.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:02:00Z"), null));
        });

        start();
        FakeLauncher launcher = context.getBean(FakeLauncher.class);
        Thread.sleep(1500);
        assertThat(state(running)).as("still running: its window is still open").isEqualTo(SessionState.IN_PROGRESS);
        assertThat(state(waiting)).as("its repository is busy").isEqualTo(SessionState.QUEUED);
        assertThat(launcher.launched).isEmpty();

        leaveEndRecords(running, shared, "succeeded");
        window.destroyForcibly();

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(running)).isEqualTo(SessionState.DONE));
        FixSession done = repository().find(running).orElseThrow();
        assertThat(done.headStart()).isEqualTo("startsha");
        assertThat(done.headEnd()).isEqualTo("endsha");
        assertThat(done.outcome()).isEqualTo("succeeded");
        await().atMost(WAIT).untilAsserted(() -> assertThat(launcher.launched).containsExactly(waiting));
    }

    @Test
    void aWindowThatEndedWhileTheEngineWasDownIsFinalizedFromItsExitRecord() throws Exception {
        Path repository = repo("shop");
        Process gone = finished();
        String id = UUID.randomUUID().toString();
        seed(r -> {
            r.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            r.markStarted(id, "window", Instant.parse("2026-11-10T09:01:00Z"), gone.pid(),
                    gone.info().startInstant().orElse(Instant.parse("2026-11-10T09:01:00Z")), "startsha", false);
        });
        leaveEndRecords(id, repository, "partially_succeeded");

        start();

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(id)).isEqualTo(SessionState.PARTIAL));
        FixSession s = repository().find(id).orElseThrow();
        assertThat(s.exitCode()).isZero();
        assertThat(s.headEnd()).isEqualTo("endsha");
        assertThat(s.outcome()).isEqualTo("partially_succeeded");
        assertThat(context.getBean(FakeLauncher.class).launched).isEmpty();
        assertThat(repository().events(id)).extracting(SessionEvent::state).containsExactly(SessionState.PARTIAL);
    }

    @Test
    void aWindowThatIsGoneWithoutARecordMakesItsSessionInterruptedAndTheQueueGoesOn() throws Exception {
        Path repository = repo("shop");
        Process gone = finished();
        String lost = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        seed(r -> {
            r.insert(new NewSession(lost, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            r.markStarted(lost, "window", Instant.parse("2026-11-10T09:01:00Z"), gone.pid(),
                    gone.info().startInstant().orElse(Instant.parse("2026-11-10T09:01:00Z")), "startsha", false);
            r.insert(new NewSession(next, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-2",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:02:00Z"), null));
        });

        start();

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(lost)).isEqualTo(SessionState.INTERRUPTED));
        FixSession s = repository().find(lost).orElseThrow();
        assertThat(s.reason()).isEqualTo("the window was gone after a restart and left no exit record");
        assertThat(s.exitCode()).isNull();
        await().atMost(WAIT).untilAsserted(() -> assertThat(context.getBean(FakeLauncher.class).launched).containsExactly(next));
    }

    @Test
    void aReusedProcessIdWithAnotherStartTimeIsNotMistakenForTheWindow() throws Exception {
        Path repository = repo("shop");
        Process unrelated = longRunning();
        String id = UUID.randomUUID().toString();
        seed(r -> {
            r.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            // The recorded start time is an hour away from the real one: the id now belongs to a different process.
            r.markStarted(id, "window", Instant.parse("2026-11-10T09:01:00Z"), unrelated.pid(),
                    unrelated.info().startInstant().orElseThrow().minusSeconds(3600), "startsha", false);
        });

        start();

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(id)).isEqualTo(SessionState.INTERRUPTED));
        assertThat(unrelated.isAlive()).as("the unrelated process is left alone").isTrue();
    }

    // ---- sessions that worked in a worktree ----

    private static String git(Path folder, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out.strip();
    }

    /** A real repository with a real worktree on its own branch, and the commits the agent made in it. */
    private dev.subscriber.store.Worktree realWorktree(Path repository, String sessionId, int commits) throws Exception {
        Path real = repository.toRealPath();
        git(real, "init", "-q");
        Files.writeString(real.resolve("a.txt"), "one\n");
        git(real, "add", "a.txt");
        git(real, "commit", "-q", "-m", "first");
        String base = git(real, "rev-parse", "HEAD");
        String id8 = sessionId.substring(0, 8);
        Path work = real.getParent().resolve(real.getFileName() + "-fix-" + id8);
        git(real, "worktree", "add", "-q", "-b", "appmod/fix-" + id8, work.toString(), base);
        for (int i = 1; i <= commits; i++) {
            Files.writeString(work.resolve("fix" + i + ".txt"), i + "\n");
            git(work, "add", "fix" + i + ".txt");
            git(work, "commit", "-q", "-m", "fix " + i);
        }
        return new dev.subscriber.store.Worktree(real.getFileName() + "-fix-" + id8, work.toString(), "appmod/fix-" + id8, base);
    }

    @Test
    void aWindowThatEndedWhileTheEngineWasDownIsFinalizedFromItsWorktree() throws Exception {
        Path repository = repo("shop");
        Process gone = finished();
        String id = UUID.randomUUID().toString();
        dev.subscriber.store.Worktree worktree = realWorktree(repository, id, 2);
        seed(r -> {
            r.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            r.setWorktree(id, worktree);
            r.markStarted(id, "window", Instant.parse("2026-11-10T09:01:00Z"), gone.pid(),
                    gone.info().startInstant().orElse(Instant.parse("2026-11-10T09:01:00Z")), worktree.baseCommit(), false);
        });
        leaveEndRecords(id, Path.of(worktree.path()), "succeeded");
        // A stale, hostile report in the checkout must not decide anything.
        Path stale = repository.resolve(".appmod/result.json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{\"status\": \"failed\"}");

        start("worktree");

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(id)).isEqualTo(SessionState.DONE));
        FixSession s = repository().find(id).orElseThrow();
        assertThat(s.outcome()).isEqualTo("succeeded");
        assertThat(s.fixCommits()).as("counted with real git after the restart").isEqualTo(2);
        assertThat(s.workPath()).isEqualTo(worktree.path());
        assertThat(repository().events(id).getLast().detail()).isEqualTo("2 commits on " + worktree.branch());
        assertThat(context.getBean(FakeLauncher.class).launched).isEmpty();
    }

    @Test
    void aWindowThatIsStillOpenInItsWorktreeIsFollowedAndFinalizedFromTheWorktree() throws Exception {
        Path repository = repo("shop");
        Process window = longRunning();
        String id = UUID.randomUUID().toString();
        dev.subscriber.store.Worktree worktree = realWorktree(repository, id, 1);
        seed(r -> {
            r.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            r.setWorktree(id, worktree);
            r.markStarted(id, "window", Instant.parse("2026-11-10T09:01:00Z"), window.pid(),
                    window.info().startInstant().orElseThrow(), worktree.baseCommit(), false);
        });

        start("worktree");
        Thread.sleep(1500);
        assertThat(state(id)).as("its window is still open").isEqualTo(SessionState.IN_PROGRESS);

        leaveEndRecords(id, Path.of(worktree.path()), "partially_succeeded");
        window.destroyForcibly();

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(id)).isEqualTo(SessionState.PARTIAL));
        FixSession s = repository().find(id).orElseThrow();
        assertThat(s.fixCommits()).isEqualTo(1);
        assertThat(s.headStart()).isEqualTo(worktree.baseCommit());
        assertThat(s.workBranch()).isEqualTo(worktree.branch());
    }

    @Test
    void aWindowThatIsGoneWithoutARecordIsInterruptedAndTheCommitsAlreadyMadeAreCounted() throws Exception {
        Path repository = repo("shop");
        Process gone = finished();
        String id = UUID.randomUUID().toString();
        dev.subscriber.store.Worktree worktree = realWorktree(repository, id, 3);
        seed(r -> {
            r.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-1",
                    repository.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T09:00:00Z"), null));
            r.setWorktree(id, worktree);
            r.markStarted(id, "window", Instant.parse("2026-11-10T09:01:00Z"), gone.pid(),
                    gone.info().startInstant().orElse(Instant.parse("2026-11-10T09:01:00Z")), worktree.baseCommit(), false);
        });

        start("worktree");

        await().atMost(WAIT).untilAsserted(() -> assertThat(state(id)).isEqualTo(SessionState.INTERRUPTED));
        FixSession s = repository().find(id).orElseThrow();
        assertThat(s.fixCommits()).as("the work is on the branch whatever happened to the window").isEqualTo(3);
        assertThat(repository().events(id).getLast().detail()).isEqualTo("3 commits on " + worktree.branch());
    }
}
