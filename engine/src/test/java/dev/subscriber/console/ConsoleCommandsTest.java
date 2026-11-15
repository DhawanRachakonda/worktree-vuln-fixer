package dev.subscriber.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.NeverLaunchConfig;
import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.shell.core.command.CommandContext;
import org.springframework.shell.core.command.CommandExecutor;
import org.springframework.shell.core.command.CommandRegistry;
import org.springframework.shell.core.command.DefaultCommandParser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the real commands through Spring Shell's own parser and executor, the way a typed line would be. */
@SpringBootTest(properties = {"spring.main.allow-bean-definition-overriding=true", "subscriber.nats.url=nats://127.0.0.1:1",
        "subscriber.nats.token=nats-secret-token-value", "subscriber.access.token=api-secret-token-value"})
@Import(NeverLaunchConfig.class)
class ConsoleCommandsTest {

    private static final Path DATA = tempDir();
    private static final Path ROOT = tempDir();

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("subscriber-console-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("subscriber.data-dir", () -> DATA.toString().replace('\\', '/'));
        registry.add("subscriber.allowed-roots", () -> ROOT.toString().replace('\\', '/'));
        // Where the runner is headless (Linux), the engine starts only with a permission decision; these tests do not run an agent.
        registry.add("subscriber.copilot.permission-mode", () -> "allow-all-tools");
    }

    @Autowired
    CommandRegistry registry;

    @Autowired
    FixSessionRepository sessions;

    @Autowired
    JdbcClient jdbc;

    private final List<String> ids = new ArrayList<>();

    @BeforeEach
    void seed() {
        ids.add(insert(SessionState.QUEUED, "report-1", null));
        ids.add(insert(SessionState.QUEUED, "report-2", null));
        ids.add(insert(SessionState.QUEUED, "report-3", null));
        sessions.setWorktree(ids.get(0), new dev.subscriber.store.Worktree("shop-fix-0a1b2c3d", "C:/work/shop-fix-0a1b2c3d",
                "appmod/fix-0a1b2c3d", "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432"));
        sessions.markStarted(ids.get(0), "window", Instant.parse("2026-11-10T10:01:00Z"), 4242L, Instant.parse("2026-11-10T10:00:59Z"),
                "headsha", false);
        sessions.markFinal(ids.get(0), SessionState.DONE, Instant.parse("2026-11-10T10:30:00Z"), 0, "endsha", "succeeded", null, 2);
        sessions.appendEvent(ids.get(0), Instant.parse("2026-11-10T10:00:00Z"), SessionState.QUEUED, "received");
        sessions.appendEvent(ids.get(0), Instant.parse("2026-11-10T10:30:00Z"), SessionState.DONE, "succeeded");
        sessions.markFinal(ids.get(1), SessionState.ERROR, Instant.parse("2026-11-10T11:00:00Z"), 7, null, null,
                "failed\u001b[31m red\u0007 \nsecond line");
    }

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM fix_session_event").update();
        jdbc.sql("DELETE FROM fix_session").update();
        ids.clear();
    }

    private String insert(SessionState state, String report, String reason) {
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, state, "securityaudit.issuefixesAgent", "Security audit", report, "C:/work/shop",
                "security-fixer", "copilot", Instant.parse("2026-11-10T10:00:00Z"), reason));
        return id;
    }

    private String run(String line) {
        StringWriter text = new StringWriter();
        new CommandExecutor(registry).execute(new CommandContext(new DefaultCommandParser(registry).parse(line), registry,
                new PrintWriter(text, true), null));
        return text.toString();
    }

    @Test
    void theFourReadCommandsAreRegistered() {
        assertThat(registry.getCommands()).extracting(c -> c.getName()).contains("sessions", "session", "events", "config", "doctor", "reopen", "cleanup", "publish");
    }

    @Test
    void publishRefusesWhatCannotBeAReportAndSaysSoWithoutAStackTrace() {
        assertThat(run("publish " + ROOT.resolve("missing.csv").toString().replace('\\', '/'))).contains("The report does not exist");
        assertThat(run("publish " + ROOT.toString().replace('\\', '/'))).contains("The report is not a file");
    }

    @Test
    void publishRefusesASubjectTheEngineDoesNotListenToAndNamesTheConfiguredOnes() throws IOException {
        Path report = Files.writeString(ROOT.resolve("subject-check.csv"), "id\n");

        String out = run("publish " + report.toString().replace('\\', '/') + " --subject nope.subject");

        assertThat(out).contains("not a subject this engine listens to").contains("securityaudit.issuefixesAgent");
    }

    @Test
    void publishToAnUnreachableServerSaysSoWithoutTheTokenAndWithoutRecordingASession() throws IOException {
        Path report = Files.writeString(ROOT.resolve("unreachable.csv"), "id\n");
        long before = sessions.count(null);

        String out = run("publish " + report.toString().replace('\\', '/'));

        assertThat(out).contains("NATS could not be reached at nats://127.0.0.1:1").doesNotContain("nats-secret-token-value");
        assertThat(sessions.count(null)).isEqualTo(before);
    }

    @Test
    void sessionsListsNewestFirstWithTheTotal() {
        String out = run("sessions");

        assertThat(out).contains(ids.get(0), ids.get(1), ids.get(2));
        assertThat(out.indexOf(ids.get(2))).as("newest first").isLessThan(out.indexOf(ids.get(1)));
        assertThat(out.indexOf(ids.get(1))).isLessThan(out.indexOf(ids.get(0)));
        assertThat(out).contains("Showing 3 of 3 sessions");
    }

    @Test
    void sessionsCanBeFilteredByState() {
        String out = run("sessions --state ERROR");

        assertThat(out).contains(ids.get(1)).doesNotContain(ids.get(0)).doesNotContain(ids.get(2));
        assertThat(out).contains("Showing 1 of 1 ERROR sessions");
        assertThat(run("sessions --state done")).contains(ids.get(0)).doesNotContain(ids.get(1));
        assertThat(run("sessions --state NEEDS_INPUT")).contains("No NEEDS_INPUT sessions.");
    }

    @Test
    void anUnknownStateSaysWhatIsAllowed() {
        assertThat(run("sessions --state BOGUS")).contains("Unknown state 'BOGUS'").contains("QUEUED").contains("REJECTED");
    }

    @Test
    void sessionShowsOneRecord() {
        String out = run("session " + ids.get(0));

        assertThat(out).contains(ids.get(0), "DONE", "securityaudit.issuefixesAgent", "report-1", "C:/work/shop", "security-fixer",
                "window", "headsha", "endsha", "succeeded").contains("exit code").contains("0");
        assertThat(out).doesNotContain("4242");
    }

    @Test
    void eventsShowTheTimeline() {
        String out = run("events " + ids.get(0));

        assertThat(out).contains("QUEUED", "received", "DONE", "succeeded");
        assertThat(out.indexOf("received")).isLessThan(out.indexOf("succeeded"));
        assertThat(run("events " + ids.get(2))).contains("No events.");
    }

    @Test
    void anUnknownOrMalformedIdIsReportedAndNothingIsLookedUp() {
        assertThat(run("session " + UUID.randomUUID())).contains("No session");
        assertThat(run("events " + UUID.randomUUID())).contains("No session");
        assertThat(run("session not-an-id")).contains("No session 'not-an-id'.");
        assertThat(run("session x'; DROP TABLE fix_session; --")).doesNotContain("DONE");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM fix_session").query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void controlCharactersFromAReasonNeverReachTheTerminal() {
        String out = run("session " + ids.get(1));

        assertThat(out).contains("failed").contains("red");
        assertThat(out).doesNotContain("\u001b").doesNotContain("\u0007");
        assertThat(out.lines().filter(l -> l.startsWith("reason")).count()).as("the reason stays on one line").isEqualTo(1);
    }

    @Test
    void configShowsTheSettingsButNoSecretValue() {
        String out = run("config");

        assertThat(out).contains("data dir", "allowed roots", "max parallel", "nats url", "nats://127.0.0.1:1");
        assertThat(out).contains("nats authentication").contains("token");
        assertThat(out).contains("api access token").contains("set");
        assertThat(out).contains("securityaudit.issuefixesAgent -> security-fixer");
        assertThat(out).doesNotContain("nats-secret-token-value").doesNotContain("api-secret-token-value");
    }

    @Test
    void configListsTheAllowListRulesOnlyInTheAllowListMode() throws IOException {
        Path dir = Files.createTempDirectory("config-test");
        SubscriberProperties.Nats nats = new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null);
        SubscriberProperties.Access access = new SubscriberProperties.Access(null);
        List<String> roots = List.of(dir.toString());
        SubscriberProperties.Subject subject = new SubscriberProperties.Subject("a.b", "A", "security-fixer", null, "issues.csv");
        SubscriberProperties allowList = new SubscriberProperties(dir.toString(), roots, List.of(subject), SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_LIST, List.of("shell(git:*)", "write(C:/work/**)"),
                        List.of("shell(curl:*)")), nats, access);
        SubscriberProperties askMode = new SubscriberProperties(dir.toString(), roots, List.of(subject), SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK), nats, access);

        assertThat(ConsoleReports.config(allowList, null).replaceAll(" +", " ")).contains("permission mode allow-list")
                .contains("allowed tool shell(git:*)").contains("allowed tool write(C:/work/**)").contains("denied tool shell(curl:*)");
        assertThat(ConsoleReports.config(askMode, null).replaceAll(" +", " ")).contains("permission mode ask").doesNotContain("allowed tool");
    }

    @Test
    void reopenRefusesWhatIsNotAFinishedSessionWithoutOpeningAnything() {
        assertThat(run("reopen " + UUID.randomUUID())).contains("No session");
        assertThat(run("reopen " + ids.get(2))).contains("QUEUED").contains("only a finished session");
        assertThat(run("reopen " + ids.get(1))).contains("never started an agent");
        assertThat(sessions.events(ids.get(2))).isEmpty();
        assertThat(sessions.events(ids.get(1))).isEmpty();
    }

    @Test
    void doctorReportsEveryCheckAndPrintsNoSecret() {
        String out = run("doctor");

        assertThat(out).contains("PASS  database", "PASS  data directory", "PASS  allowed root");
        assertThat(out).contains("FAIL  nats").contains("nats://127.0.0.1:1");
        assertThat(out).contains("checks failed.");
        assertThat(out).doesNotContain("nats-secret-token-value").doesNotContain("api-secret-token-value");
    }

    // ---- worktrees ----

    private static String lineWith(String output, String needle) {
        return output.lines().filter(l -> l.contains(needle)).findFirst().orElseThrow();
    }

    @Test
    void theListMarksSessionsWithCommitsToReviewAndNoOthers() {
        String zero = insert(SessionState.QUEUED, "report-zero", null);
        sessions.setWorktree(zero, new dev.subscriber.store.Worktree("shop-fix-zero", "C:/work/shop-fix-zero", "appmod/fix-00000000",
                "a".repeat(40)));
        sessions.markFinal(zero, SessionState.ERROR, Instant.parse("2026-11-10T12:00:00Z"), 1, null, null, "no luck", 0);

        String out = run("sessions");

        assertThat(out).contains("FIXES");
        assertThat(lineWith(out, ids.get(0))).as("two commits made").matches(".*\\bDONE\\s+2\\s+.*");
        assertThat(lineWith(out, ids.get(1))).as("no worktree").matches(".*\\bERROR\\s+-\\s+.*");
        assertThat(lineWith(out, ids.get(2))).as("queued, nothing made").matches(".*\\bQUEUED\\s+-\\s+.*");
        assertThat(lineWith(out, zero)).as("a worktree with no commits is not marked").matches(".*\\bERROR\\s+-\\s+.*");
    }

    @Test
    void aSessionWithAWorktreeShowsItAndTheCommandsToReviewAndRemoveIt() {
        String out = run("session " + ids.get(0));

        assertThat(out).contains("shop-fix-0a1b2c3d", "C:/work/shop-fix-0a1b2c3d", "appmod/fix-0a1b2c3d",
                "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432");
        assertThat(lineWith(out, "commits made")).contains("2");
        String base = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432..appmod/fix-0a1b2c3d";
        assertThat(out).contains("git -C \"C:/work/shop\" log " + base);
        assertThat(out).contains("git -C \"C:/work/shop\" diff " + base);
        assertThat(out).as("the folder of this fixture does not exist, so no cleanup is offered and the person is told so")
                .contains("(no longer exists)").doesNotContain("cleanup " + ids.get(0)).doesNotContain("worktree remove");
        assertThat(out).contains("git -C \"C:/work/shop\" branch -D appmod/fix-0a1b2c3d");
    }

    @Test
    void aSessionWithoutAWorktreeShowsNoWorktreeLines() {
        String out = run("session " + ids.get(1));

        assertThat(out).doesNotContain("worktree").doesNotContain("commits made").doesNotContain("git -C");
    }

    @Test
    void aWorktreeWhoseCommitsWereNotCountedSaysSo() {
        String id = insert(SessionState.QUEUED, "report-uncounted", null);
        sessions.setWorktree(id, new dev.subscriber.store.Worktree("shop-fix-xx", "C:/work/shop-fix-xx", "appmod/fix-11111111", "a".repeat(40)));
        sessions.markFinal(id, SessionState.INTERRUPTED, Instant.parse("2026-11-10T12:00:00Z"), null, null, null, "gone");

        assertThat(lineWith(run("session " + id), "commits made")).contains("not counted");
    }

    @Test
    void controlCharactersInARecordedWorktreeNeverReachTheTerminal() {
        String id = insert(SessionState.QUEUED, "report-hostile", null);
        sessions.setWorktree(id, new dev.subscriber.store.Worktree("n\u001b[31mame", "C:/work/p\u001b]0;title\u0007", "appmod/fix-\u001b[2J1",
                "a".repeat(40)));
        sessions.markFinal(id, SessionState.DONE, Instant.parse("2026-11-10T12:00:00Z"), 0, null, "succeeded", null, 1);

        String out = run("session " + id);

        assertThat(out).doesNotContain("\u001b").doesNotContain("\u0007");
        assertThat(out).contains("git -C");
    }

    @Test
    void aRecordedValueWithAQuoteGetsNoCommandsInsteadOfABrokenOne() {
        String id = insert(SessionState.QUEUED, "report-quote", null);
        sessions.setWorktree(id, new dev.subscriber.store.Worktree("n", "C:/work/a\"b", "appmod/fix-22222222", "a".repeat(40)));
        sessions.markFinal(id, SessionState.DONE, Instant.parse("2026-11-10T12:00:00Z"), 0, null, "succeeded", null, 1);

        String out = run("session " + id);

        assertThat(out).contains("the git commands are not shown").doesNotContain("worktree remove");
    }

    @Test
    void theConsoleNeverRunsTheCommandsItShows() {
        // The folder from the seeded record does not exist: if the console ran anything, something would fail or appear.
        String before = run("session " + ids.get(0));
        run("session " + ids.get(0));
        assertThat(run("session " + ids.get(0))).isEqualTo(before);
        assertThat(sessions.find(ids.get(0)).orElseThrow().fixCommits()).isEqualTo(2);
    }

    @Test
    void aSessionWhoseFolderExistsPointsToTheCleanupCommandAndNotToAForcedRemoval() throws IOException {
        String id = finishedWithFolder();

        String out = run("session " + id);

        assertThat(out).contains("cleanup " + id + "   (add --yes to do it; the branch and its commits stay)")
                .doesNotContain("(no longer exists)").doesNotContain("worktree remove").contains("branch -D appmod/fix-0a1b2c3d")
                .contains("shop-fix-0a1b2c3d-").contains("status --short");
    }

    @Test
    void cleanupWithoutYesOnlySaysWhatItWouldDoAndWithYesReachesTheService() throws IOException {
        String id = finishedWithFolder();

        String dry = run("cleanup " + id);
        assertThat(dry).contains("Would remove the worktree folder").contains("--yes").doesNotContain("Not removed");

        String confirmed = run("cleanup " + id + " --yes");
        assertThat(confirmed).as("the option was parsed and the service ran; git does not know this folder").startsWith("Not removed:");
        assertThat(sessions.events(id)).as("nothing was removed, so no event").isEmpty();
    }

    @Test
    void cleanupRefusesAnUnknownSessionAndAFolderThatIsGone() {
        assertThat(run("cleanup not-a-session --yes").strip()).isEqualTo("No session 'not-a-session'.");
        assertThat(run("cleanup " + ids.get(0) + " --yes")).contains("is already gone");
    }

    @Test
    void reopenAfterTheFolderIsGoneIsRefused() {
        assertThat(run("reopen " + ids.get(0))).contains("no longer exists");
    }

    @Test
    void anEventTimestampWithMicrosecondsIsStillSeparatedFromTheState() {
        sessions.appendEvent(ids.get(0), Instant.parse("2026-11-12T18:35:01.841635Z"), SessionState.QUEUED, "received");

        String out = run("events " + ids.get(0));

        assertThat(out).contains("2026-11-12T18:35:01.841635Z ").doesNotContain("841635ZQUEUED");
        assertThat(lineWith(out, "received")).matches(".*Z\s+QUEUED\s+received.*");
    }

    private String finishedWithFolder() throws IOException {
        Path folder = Files.createDirectories(ROOT.resolve("shop-fix-0a1b2c3d-" + UUID.randomUUID()));
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-folder",
                "C:/work/cleanup-shop", "security-fixer", "copilot", Instant.parse("2026-11-10T10:00:00Z"), null));
        sessions.setWorktree(id, new dev.subscriber.store.Worktree("shop-fix-0a1b2c3d", folder.toString().replace('\\', '/'),
                "appmod/fix-0a1b2c3d", "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432"));
        sessions.markStarted(id, "window", Instant.parse("2026-11-10T10:00:01Z"), 1L, Instant.parse("2026-11-10T10:00:00Z"), "h", false);
        sessions.markFinal(id, SessionState.DONE, Instant.parse("2026-11-10T10:05:00Z"), 0, "e", "ok", null, 2);
        return id;
    }

    @Test
    void configShowsTheSelectedToolAndItsCommand() throws IOException {
        Path dir = Files.createTempDirectory("config-tool-test");
        SubscriberProperties.Subject subject = new SubscriberProperties.Subject("a.b", "A", "security-fixer", null, "issues.csv");
        SubscriberProperties claude = new SubscriberProperties(dir.toString(), List.of(dir.toString()), List.of(subject), SubscriberProperties.Runner.HEADLESS, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.NONE, SubscriberProperties.AgentTool.CLAUDE, new SubscriberProperties.Claude("claude-x"));

        assertThat(ConsoleReports.config(claude, null).replaceAll(" +", " ")).contains("agent tool claude").contains("claude command claude-x")
                .doesNotContain("copilot command");
    }

    @Test
    void aQueuedSessionSaysWhichSessionOfTheSameRepositoryItWaitsBehind() {
        // ids.get(2) is queued and the earlier two are final, so nothing blocks it yet
        assertThat(run("session " + ids.get(2))).doesNotContain("waiting");
        String running = insert(SessionState.QUEUED, "report-4", null);
        sessions.markStarted(running, "window", Instant.parse("2026-11-10T12:00:00Z"), 1L, Instant.parse("2026-11-10T12:00:00Z"), "h", false);
        String waiting = insert(SessionState.QUEUED, "report-5", null);

        String shown = run("session " + waiting);
        assertThat(shown).contains("waiting").contains("behind " + ids.get(2)).contains("one session at a time");
        assertThat(run("sessions --state QUEUED")).contains("waits behind " + ids.get(2).substring(0, 8));
        assertThat(run("session " + running)).doesNotContain("waiting");
    }

    @Test
    void aSessionShowsItsSourceBranchHowItWasChosenLocalChangesAndThePublishOutcome() {
        String id = ids.get(0);
        sessions.setSource(id, "release/1.2", "publish", true);
        sessions.setPublishState(id, "pushed");
        sessions.setPullRequest(id, "created", "https://host.example/pr/7");

        String shown = run("session " + id);

        assertThat(shown).contains("source branch").contains("release/1.2").contains("source chosen by").contains("publish")
                .contains("local changes").contains("included").contains("pushed").contains("created")
                .contains("https://host.example/pr/7");
        assertThat(run("session " + ids.get(1))).doesNotContain("source branch").doesNotContain("pull request");
    }

    @Test
    void publishTakesTheSourceBranchAndLocalChangesOptionsAndRefusesBothTogether() throws IOException {
        Path report = Files.writeString(ROOT.resolve("options-check.csv"), "id\n");
        String path = report.toString().replace('\\', '/');

        assertThat(run("publish " + path + " --source-branch main --unsafe-local-changes")).contains("Choose one");
        assertThat(run("publish " + path + " --source-branch a..b")).contains("not usable");
        assertThat(run("publish " + path + " --unsafe-local-changes")).doesNotContain("Choose one");
    }

    private static String git(Path folder, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("git %s: %s", String.join(" ", args), out).isZero();
        return out.strip();
    }

    /** A session that ended DONE with one commit on a real worktree whose repository has a bare origin. */
    private String finishedSessionWithWork(String name, boolean localChanges) throws Exception {
        Path root = ROOT.toRealPath();
        Path repo = Files.createDirectories(root.resolve(name));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "first");
        Path bare = root.resolve(name + "-origin.git");
        git(root, "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());
        String id = UUID.randomUUID().toString();
        String branch = "appmod/fix-" + id.substring(0, 8);
        Path work = root.resolve(name + "-fix-" + id.substring(0, 8));
        git(repo, "worktree", "add", "-q", "-b", branch, work.toString());
        Files.writeString(work.resolve("fix.txt"), "fixed\n");
        git(work, "add", "fix.txt");
        git(work, "commit", "-q", "-m", "fix");
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit", "report-" + name,
                repo.toString(), "security-fixer", "copilot", Instant.parse("2026-11-10T10:00:00Z"), null, null, localChanges));
        sessions.setWorktree(id, new dev.subscriber.store.Worktree(work.getFileName().toString(), work.toString(), branch,
                git(repo, "rev-parse", "HEAD")));
        sessions.markStarted(id, "window", Instant.parse("2026-11-10T10:01:00Z"), 1L, Instant.parse("2026-11-10T10:00:59Z"), "h", false);
        sessions.markFinal(id, SessionState.DONE, Instant.parse("2026-11-10T10:30:00Z"), 0, "e", "succeeded", null, 1);
        return id;
    }

    @Test
    void publishBranchRefusesAnUnknownAnUnfinishedAndAnErroredSession() {
        assertThat(run("publish-branch " + UUID.randomUUID() + " --yes")).contains("No session");
        assertThat(run("publish-branch " + ids.get(2) + " --yes")).contains("Nothing was pushed").contains("DONE or PARTIAL");
        assertThat(run("publish-branch " + ids.get(1) + " --yes")).contains("Nothing was pushed").contains("ERROR");
    }

    @Test
    void publishBranchPushesAfterConfirmationAndOnlyThen() throws Exception {
        String id = finishedSessionWithWork("pb-shop", true);
        sessions.setPublishState(id, "awaiting_confirmation");
        Path bare = ROOT.toRealPath().resolve("pb-shop-origin.git");
        String branch = "appmod/fix-" + id.substring(0, 8);

        assertThat(run("session " + id)).contains("awaiting_confirmation").contains("publish-branch " + id + " --yes");
        assertThat(run("publish-branch " + id)).contains("Would push").contains(branch).contains("uncommitted local work");
        assertThat(git(bare, "branch", "--list")).doesNotContain(branch);

        assertThat(run("publish-branch " + id + " --yes")).contains("pushed " + branch);

        assertThat(git(bare, "branch", "--list")).contains(branch);
        assertThat(run("session " + id)).contains("pushed");
    }

    @Test
    void configShowsThePublishAndBranchSettingsFromSettingsOnlyEvenWhenTheRemoteIsUnreachable() throws Exception {
        Path repo = Files.createDirectories(ROOT.resolve("cfg-shop"));
        Files.createDirectories(repo.resolve(".appmod"));
        Files.writeString(repo.resolve(".appmod/publish.json"), "{\"sourceBranch\":\"develop\",\"mode\":\"push\"}");
        SubscriberProperties.Subject subject = new SubscriberProperties.Subject("securityaudit.issuefixesAgent", "Security audit", "security-fixer",
                repo.toString(), null);
        SubscriberProperties props = new SubscriberProperties(DATA.toString().replace('\\', '/'), List.of(ROOT.toString()), List.of(subject),
                SubscriberProperties.Runner.HEADLESS, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ASK),
                new SubscriberProperties.Nats("nats://127.0.0.1:1", null, null, null, null), new SubscriberProperties.Access(null),
                SubscriberProperties.Isolation.WORKTREE, SubscriberProperties.AgentTool.COPILOT, new SubscriberProperties.Claude("claude"),
                new SubscriberProperties.Publish(SubscriberProperties.PublishMode.PULL_REQUEST, "gh pr create --base {base}"));

        long start = System.nanoTime();
        String out = ConsoleReports.config(props, null).replaceAll(" +", " ");

        assertThat((System.nanoTime() - start) / 1_000_000L).as("milliseconds, no network").isLessThan(2000);
        assertThat(out).contains("publish mode pull-request").contains("pull-request command gh pr create --base {base}")
                .contains("source branch per repository").contains("local changes not included unless")
                .contains("cfg-shop").contains("source branch develop, mode push");
        Files.writeString(repo.resolve(".appmod/publish.json"), "{nope");
        assertThat(ConsoleReports.config(props, null)).contains("INVALID").contains(".appmod/publish.json");
    }
}
