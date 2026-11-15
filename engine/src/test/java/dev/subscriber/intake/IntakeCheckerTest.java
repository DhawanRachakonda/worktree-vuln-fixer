package dev.subscriber.intake;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Subject;
import dev.subscriber.intake.IntakeChecker.Accepted;
import dev.subscriber.intake.IntakeChecker.Outcome;
import dev.subscriber.intake.IntakeChecker.Rejected;
import dev.subscriber.store.FixSessionRepository;
import dev.subscriber.store.FixSessionRepository.NewSession;
import dev.subscriber.store.SessionState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

@JdbcTest
@Import(FixSessionRepository.class)
class IntakeCheckerTest {

    private static final String LOCATION = ".appmod/security-audit/issues.csv";

    @TempDir
    Path root;

    @TempDir
    Path outside;

    @Autowired
    FixSessionRepository sessions;

    private IntakeChecker checker(Subject... subjects) {
        SubscriberProperties props = new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'),
                List.of(root.toString()), List.of(subjects), SubscriberProperties.Runner.AUTO, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
        return new IntakeChecker(props, sessions);
    }

    private static Subject audit() {
        return new Subject("securityaudit.issuefixesAgent", "Security audit", "appmod-harness", null, LOCATION);
    }

    /** A git repository folder with an audit report in the expected place. */
    private static Path repoWithReport(Path parent, String name) throws IOException {
        Path repo = parent.resolve(name);
        Files.createDirectories(repo.resolve(".git"));
        Path report = repo.resolve(LOCATION);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "file,line,title\r\n");
        return report;
    }

    private static String reasonOf(Outcome o) {
        assertThat(o).isInstanceOf(Rejected.class);
        return ((Rejected) o).reason();
    }

    @Test
    void anAuditReportInsideAnAllowedRootIsAcceptedAndTheRepositoryIsDerived() throws IOException {
        Path report = repoWithReport(root, "shop");
        Outcome o = checker(audit()).check(audit(), report.toString());
        assertThat(o).isInstanceOf(Accepted.class);
        assertThat(((Accepted) o).report()).isEqualTo(report.toRealPath());
        assertThat(((Accepted) o).repository()).isEqualTo(root.resolve("shop").toRealPath());
    }

    @Test
    void folderNamesWithShellCharactersAreAcceptedExactly() throws IOException {
        Path report = repoWithReport(root, "r&d 100% ^'x (1)");
        Outcome o = checker(audit()).check(audit(), report.toString());
        assertThat(o).isInstanceOf(Accepted.class);
        assertThat(((Accepted) o).repository().getFileName().toString()).isEqualTo("r&d 100% ^'x (1)");
    }

    @Test
    void aPathWithDotDotThatStaysInsideIsAcceptedAndOneThatLeavesIsRefused() throws IOException {
        Path report = repoWithReport(root, "shop");
        String inside = root + "/shop/.appmod/../.appmod/security-audit/issues.csv";
        assertThat(checker(audit()).check(audit(), inside)).isInstanceOf(Accepted.class);

        Path elsewhere = repoWithReport(outside, "other");
        String escape = root + "/shop/../../" + outside.getFileName() + "/other/" + LOCATION;
        assertThat(Files.exists(Path.of(escape))).as("the escaping path really exists").isTrue();
        assertThat(reasonOf(checker(audit()).check(audit(), escape))).contains("outside the allowed roots");
        assertThat(report).exists();
        assertThat(elsewhere).exists();
    }

    @Test
    void aReportOutsideEveryAllowedRootIsRefused() throws IOException {
        Path report = repoWithReport(outside, "other");
        assertThat(reasonOf(checker(audit()).check(audit(), report.toString()))).contains("outside the allowed roots");
    }

    @Test
    void aLinkInsideTheRootThatLeadsOutsideIsRefused() throws Exception {
        Path target = repoWithReport(outside, "other").getParent().getParent().getParent();
        Path link = root.resolve("looks-inside");
        if (!makeLink(link, target)) {
            org.junit.jupiter.api.Assumptions.abort("neither a symbolic link nor a junction can be created here");
        }
        String viaLink = link + "/" + LOCATION;
        assertThat(Files.isRegularFile(Path.of(viaLink))).as("the report is reachable through the link").isTrue();
        assertThat(reasonOf(checker(audit()).check(audit(), viaLink))).contains("outside the allowed roots");
    }

    @Test
    void payloadsThatAreNotASingleExistingRegularFileAreRefused() throws IOException {
        Path report = repoWithReport(root, "shop");
        IntakeChecker checker = checker(audit());
        assertThat(reasonOf(checker.check(audit(), ""))).contains("empty");
        assertThat(reasonOf(checker.check(audit(), null))).contains("empty");
        assertThat(reasonOf(checker.check(audit(), report + "\nextra"))).contains("line breaks");
        assertThat(reasonOf(checker.check(audit(), report + "\r"))).contains("line breaks");
        assertThat(reasonOf(checker.check(audit(), report + "\0"))).contains("null characters");
        assertThat(reasonOf(checker.check(audit(), "relative/path.csv"))).contains("absolute");
        assertThat(reasonOf(checker.check(audit(), root.resolve("nothing.csv").toString()))).contains("does not exist");
        assertThat(reasonOf(checker.check(audit(), root.resolve("shop").toString()))).contains("not a regular file");
        assertThat(reasonOf(checker.check(audit(), root + "/a<b>|c?.csv"))).containsAnyOf("not a valid path", "does not exist");
    }

    @Test
    void aReportNotAtTheExpectedLocationGivesNoRepository() throws IOException {
        Path wrong = root.resolve("shop/other/issues.csv");
        Files.createDirectories(wrong.getParent());
        Files.createDirectories(root.resolve("shop/.git"));
        Files.writeString(wrong, "x");
        assertThat(reasonOf(checker(audit()).check(audit(), wrong.toString())))
                .contains("repository could not be determined").contains(LOCATION);
    }

    @Test
    void aRepositoryWithoutGitIsRefused() throws IOException {
        Path report = root.resolve("plain").resolve(LOCATION);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "x");
        assertThat(reasonOf(checker(audit()).check(audit(), report.toString()))).contains("no .git entry");
    }

    @Test
    void aGitFileInsteadOfAFolderCountsAsARepositoryForWorktrees() throws IOException {
        Path report = root.resolve("worktree").resolve(LOCATION);
        Files.createDirectories(report.getParent());
        Files.writeString(report, "x");
        Files.writeString(root.resolve("worktree/.git"), "gitdir: C:/somewhere/.git/worktrees/w");
        assertThat(checker(audit()).check(audit(), report.toString())).isInstanceOf(Accepted.class);
    }

    @Test
    void aSubjectWithAFixedRepositoryUsesIt() throws IOException {
        Files.createDirectories(root.resolve("fixed/.git"));
        Path report = root.resolve("reports/coverity.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{}");
        Subject coverity = new Subject("coverity.issuefixesAgent", "Coverity static-analysis", "appmod-harness",
                root.resolve("fixed").toString(), null);
        Outcome o = checker(coverity).check(coverity, report.toString());
        assertThat(o).isInstanceOf(Accepted.class);
        assertThat(((Accepted) o).repository()).isEqualTo(root.resolve("fixed").toRealPath());
    }

    @Test
    void aFixedRepositoryOutsideTheRootsIsRefusedAndASubjectWithoutOneIsRefused() throws IOException {
        Files.createDirectories(outside.resolve("elsewhere/.git"));
        Path report = root.resolve("coverity.json");
        Files.writeString(report, "{}");
        Subject out = new Subject("coverity.issuefixesAgent", "Coverity static-analysis", "appmod-harness",
                outside.resolve("elsewhere").toString(), null);
        assertThat(reasonOf(checker(out).check(out, report.toString()))).contains("repository is outside the allowed roots");

        Subject none = new Subject("bdh.issuefixesAgent", "Black Duck Hub dependency-security", "appmod-harness", null, null);
        assertThat(reasonOf(checker(none).check(none, report.toString()))).contains("no repository is configured");
    }

    @Test
    void aReportThatAlreadyHasAQueuedOrRunningSessionIsADuplicateUntilItIsFinished() throws IOException {
        Path report = repoWithReport(root, "shop");
        IntakeChecker checker = checker(audit());
        String id = UUID.randomUUID().toString();
        sessions.insert(new NewSession(id, SessionState.QUEUED, "securityaudit.issuefixesAgent", "Security audit",
                report.toRealPath().toString(), root.resolve("shop").toRealPath().toString(), "appmod-harness", "copilot",
                Instant.now(), null));
        assertThat(reasonOf(checker.check(audit(), report.toString()))).contains("duplicate");

        sessions.markStarted(id, "window", Instant.now(), 1L, Instant.now(), "h", false);
        assertThat(reasonOf(checker.check(audit(), report.toString()))).contains("duplicate");

        sessions.markFinal(id, SessionState.DONE, Instant.now(), 0, "h", "succeeded", null);
        assertThat(checker.check(audit(), report.toString())).isInstanceOf(Accepted.class);
    }

    /** A symbolic link where the account may create one, else a junction (no privilege needed on Windows). */
    private static boolean makeLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                return false;
            }
        }
        Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.exists(link);
    }
}
