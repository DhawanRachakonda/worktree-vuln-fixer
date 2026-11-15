import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Checks for FindingsToCsv.java and ValidateRun.java. Single file, JDK only; the tools run as separate processes, as
 * users run them. The CSV is read back with the independent RFC 4180 reader below, not with the tool's code.
 *
 * <pre>
 *   java tests/security-audit/ToolTests.java [kit-root]        (kit-root defaults to the current directory)
 * </pre>
 *
 * Exit code 0 when every check passes, 1 otherwise. The validators themselves are tested by ValidatorTests.java.
 */
public final class ToolTests {

    private static int passed;
    private static int failed;
    private static int skipped;
    private static Path kit;
    private static Path fixtureOk;
    private static Path tool;
    private static Path repoOk;
    private static Path validateRun;
    private static final String SELECT_ANCHOR = "\"line\": 77,\n        \"description\": \"\\\"select";
    private static Path scratch;

    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        fixtureOk = kit.resolve("tests/security-audit/fixtures/run-ok");
        repoOk = kit.resolve("tests/security-audit/fixtures/repo-ok");
        tool = kit.resolve(".github/appmod/tools/FindingsToCsv.java");
        validateRun = kit.resolve(".github/appmod/tools/ValidateRun.java");
        scratch = Files.createTempDirectory("security-audit-tests-");
        try {
            happyPath();
            roundTrip();
            attackClassMissing();
            formulaGuard();
            failures();
            locations();
            emptyFindings();
            usageErrors();
            validateRunChecks();
            everyToolLaunches();
        } finally {
            deleteTree(scratch);
        }
        System.out.printf("%n%d passed, %d failed, %d skipped%n", passed, failed, skipped);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------------------
    // FindingsToCsv: the fixture run
    // ------------------------------------------------------------------------------------------------------------

    private static void happyPath() throws Exception {
        Path out = scratch.resolve("out-ok");
        Result r = runTool(fixtureOk, out);
        check("fixture run exits 0", r.exit == 0, r.stderr);
        byte[] bytes = Files.readAllBytes(out.resolve("issues.csv"));
        check("CSV starts with a UTF-8 BOM", bytes.length > 3 && (bytes[0] & 0xff) == 0xEF && (bytes[1] & 0xff) == 0xBB
                && (bytes[2] & 0xff) == 0xBF);
        List<List<String>> rows = parse(new String(bytes, StandardCharsets.UTF_8));
        List<String> header = rows.get(0);
        check("header has the 17 documented columns in order", header.equals(List.of("file", "line", "title",
                "fingerprint", "verdict", "severity", "confidence", "location_note", "description", "root_cause",
                "trace", "remediation", "blockers", "validation_plan", "attack_class", "commit", "generated_at")),
                header.toString());
        check("one row per evidence location of confirmed and needs_validation findings (4 rows + header)",
                rows.size() == 5, "rows=" + rows.size());
        check("every row has 17 cells", rows.stream().allMatch(row -> row.size() == 17));
        List<List<String>> data = rows.subList(1, rows.size());
        check("rejected findings are absent", data.stream().noneMatch(row -> row.get(3).startsWith("xss:")));
        check("minimum columns are filled on every row", data.stream().allMatch(row -> !row.get(0).isEmpty()
                && row.get(1).matches("[1-9][0-9]*") && !row.get(2).isEmpty()));
        check("sorted: critical, high, high, then the unproven issue",
                List.of("critical", "high", "high", "").equals(data.stream().map(row -> row.get(5)).toList()),
                data.stream().map(row -> row.get(5)).toList().toString());
        check("high rows are ordered by file then line", data.get(1).get(0).endsWith("UserController.java")
                && data.get(2).get(0).endsWith("UserDao.java"));
        List<String> unproven = data.get(3);
        check("unproven row: verdict, empty severity and confidence, blockers and plan present",
                unproven.get(4).equals("needs_validation") && unproven.get(5).isEmpty() && unproven.get(6).isEmpty()
                        && unproven.get(12).contains("Gateway ownership policy") && unproven.get(13).startsWith("local:"));
        check("confirmed row carries remediation and no blockers", !data.get(0).get(11).isEmpty()
                && data.get(0).get(12).isEmpty());
        check("attack class is joined from the coverage ledger (several classes joined)",
                data.get(0).get(14).equals("Access control") && data.get(1).get(14).equals("Injection")
                        && unproven.get(14).equals("Access control; Data isolation"), unproven.get(14));
        check("commit comes from run-metadata source_ref", data.get(0).get(15).contains(
                "0123456789abcdef0123456789abcdef01234567"));
        check("generated_at is a UTC timestamp", data.get(0).get(16).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"));
        check("trace summarises entry point to sink", data.get(1).get(10).startsWith("entrypoint ")
                && data.get(1).get(10).contains(" -> sink "));
        String[] last = r.stdout.strip().split("\\R");
        String summary = last[last.length - 1];
        check("last stdout line is the JSON summary with the counts", summary.contains("\"issues\":3")
                && summary.contains("\"rows\":4") && summary.contains("\"confirmed\":2")
                && summary.contains("\"needs_validation\":1") && summary.contains("\"rejected\":1")
                && summary.contains("\"critical\":1") && summary.contains("\"high\":1"), summary);
        check("the tool wrote only issues.csv into the output directory", listNames(out).equals(List.of("issues.csv")),
                listNames(out).toString());
    }

    private static void roundTrip() throws Exception {
        Path out = scratch.resolve("out-roundtrip");
        runTool(fixtureOk, out);
        List<List<String>> data = parse(Files.readString(out.resolve("issues.csv"), StandardCharsets.UTF_8));
        List<String> admin = data.get(1);
        check("line break and non-ASCII text survive the round trip", admin.get(8).equals(
                "The reset route is registered outside the security filter chain.\nAny caller can set a new admin "
                        + "password. Café-Nutzer sind betroffen: Zugriff über 管理 Konsole."), admin.get(8));
        List<String> inj = data.get(2);
        check("commas and double quotes survive the round trip", inj.get(8).equals("The search term is concatenated "
                + "into a SQL string, so a caller can change the query: it reads rows, e.g. \"x' OR '1'='1\", from "
                + "any table."), inj.get(8));
        check("quoted code in a note survives", data.get(3).get(7).equals(
                "\"select * from users where name like '%\" + q + \"%'\""), data.get(3).get(7));
        String raw = Files.readString(out.resolve("issues.csv"), StandardCharsets.UTF_8);
        check("line ends are CRLF", raw.endsWith("\r\n") && !raw.replace("\r\n", "").replaceAll("\"[^\"]*\"", "")
                .contains("\r"));
    }

    private static void attackClassMissing() throws Exception {
        Path run = scratch.resolve("run-noledger");
        Files.createDirectories(run);
        Files.copy(fixtureOk.resolve("findings.json"), run.resolve("findings.json"));
        Files.copy(fixtureOk.resolve("run-metadata.json"), run.resolve("run-metadata.json"));
        Path out = scratch.resolve("out-noledger");
        Result r = runTool(run, out);
        check("no coverage ledger: the run still succeeds with a warning", r.exit == 0
                && r.stderr.contains("coverage-ledger.json not found"), r.stderr);
        List<List<String>> data = parse(Files.readString(out.resolve("issues.csv"), StandardCharsets.UTF_8));
        check("no coverage ledger: attack_class is empty and the rows are otherwise complete", data.size() == 5
                && data.stream().skip(1).allMatch(row -> row.get(14).isEmpty() && row.size() == 17
                && !row.get(0).isEmpty() && !row.get(2).isEmpty()));
    }

    private static void formulaGuard() throws Exception {
        Path out = scratch.resolve("out-formula");
        runTool(fixtureOk, out);
        List<List<String>> data = parse(Files.readString(out.resolve("issues.csv"), StandardCharsets.UTF_8));
        check("a title starting with '=' is written with a leading single quote",
                data.get(4).get(2).startsWith("'=HYPERLINK("), data.get(4).get(2));
        for (String trigger : List.of("+1+1", "-2+3", "@SUM(1)", "\\t=cmd", " =1+1")) {
            Path run = variant("formula-" + Math.abs(trigger.hashCode()),
                    "SQL injection in user search", trigger + " injection");
            Path o = scratch.resolve("out-formula-" + Math.abs(trigger.hashCode()));
            Result r = runTool(run, o);
            check("run with title '" + trigger.replace("\\t", "<TAB>") + "' succeeds", r.exit == 0, r.stderr);
            String title = parse(Files.readString(o.resolve("issues.csv"), StandardCharsets.UTF_8)).get(2).get(2);
            String expected = "'" + trigger.replace("\\t", "\t") + " injection";
            check("title '" + trigger.replace("\\t", "<TAB>") + "' is neutralized", title.equals(expected),
                    title);
        }
        check("line numbers are never prefixed", data.stream().skip(1).allMatch(row -> row.get(1).matches("[0-9]+")));
    }

    // ------------------------------------------------------------------------------------------------------------
    // FindingsToCsv: failures write nothing
    // ------------------------------------------------------------------------------------------------------------

    private static void failures() throws Exception {
        String lineThenSelect = "\"line\": 77,\n        \"description\": \"\\\"select";
        String descriptionOnly = "\"description\": \"\\\"select";
        failing("missing line number names the finding", variant("noline", lineThenSelect, descriptionOnly), 3,
                "inj:user-search-sql", "missing 'line'");
        failing("non-integer line is refused", variant("floatline", lineThenSelect,
                "\"line\": 77.5,\n        " + descriptionOnly), 3,
                "inj:user-search-sql", "'line' must be a whole number");
        failing("line of zero is refused", variant("zeroline", lineThenSelect,
                "\"line\": 0,\n        " + descriptionOnly), 3,
                "inj:user-search-sql", "'line' must be a whole number");
        failing("missing path names the finding", variant("nopath",
                "\"file\": \"src/main/java/app/UserDao.java\",\n        \"line\": 77,\n        \"description\": \"\\\"select",
                "\"line\": 77,\n        \"description\": \"\\\"select"), 3, "inj:user-search-sql", "missing 'file'");
        failing("absolute path is refused", variant("abspath", "\"file\": \"src/main/java/app/SecurityConfig.java\",\n"
                + "        \"line\": 44,\n        \"description\": \"requestMatchers",
                "\"file\": \"/etc/passwd\",\n        \"line\": 44,\n        \"description\": \"requestMatchers"), 3,
                "auth:admin-reset-unauthenticated", "not a repository-relative path");
        failing("parent reference in a path is refused", variant("dotdot", "\"file\": \"src/main/java/app/UserDao.java\",\n"
                + "        \"line\": 77,\n        \"description\": \"\\\"select",
                "\"file\": \"../outside/UserDao.java\",\n        \"line\": 77,\n        \"description\": \"\\\"select"),
                3, "inj:user-search-sql", "not a repository-relative path");
        failing("unknown verdict is refused", variant("verdict", "\"verdict\": \"rejected\"", "\"verdict\": \"maybe\""), 3,
                "xss:profile-name", "unknown verdict 'maybe'");
        failing("unknown field is refused (schema drift)", variant("extra", "\"reason\": \"The template engine",
                "\"surprise\": 1,\n    \"reason\": \"The template engine"), 3, "xss:profile-name",
                "unexpected field 'surprise'");
        failing("invalid severity is refused", variant("sev", "\"overall_severity\": \"high\"",
                "\"overall_severity\": \"urgent\""), 3, "inj:user-search-sql", "overall_severity");
        failing("duplicate fingerprint is refused", variant("dup", "\"fingerprint\": \"xss:profile-name\"",
                "\"fingerprint\": \"inj:user-search-sql\""), 3, "inj:user-search-sql", "more than once");
        failing("top-level object instead of array is refused", variantWhole("notarray", "{\"verdict\": \"confirmed\"}"),
                3, "", "must be a JSON array");
        failing("malformed JSON is refused", variantWhole("badjson", "[{\"verdict\": "), 3, "", "not valid JSON");
        failing("duplicate JSON key is refused", variantWhole("dupkey", "[{\"verdict\": \"rejected\", \"verdict\": "
                + "\"rejected\"}]"), 3, "", "duplicate key");
        Path missing = scratch.resolve("run-missing");
        Files.createDirectories(missing);
        Path out = scratch.resolve("out-missing");
        Result r = runTool(missing, out);
        check("missing findings.json fails with exit 3 and writes nothing", r.exit == 3
                && r.stderr.contains("findings.json not found") && !Files.exists(out.resolve("issues.csv")), r.stderr);
    }

    // ------------------------------------------------------------------------------------------------------------
    // FindingsToCsv: every cited file and line must exist in the audited repository
    // ------------------------------------------------------------------------------------------------------------

    private static void locations() throws Exception {
        String descOnly = "\"description\": \"\\\"select";
        Path atEnd = variant("loc-atend", SELECT_ANCHOR, "\"line\": 100,\n        " + descOnly);
        Result r = runTool(atEnd, scratch.resolve("out-loc-atend"));
        check("a line equal to the file's last line is accepted", r.exit == 0, r.stderr);
        check("summary says the locations were checked", lastLine(r.stdout).contains("\"locationsChecked\":true"));

        failing("a line past the end of the file is refused with file, line and file length",
                variant("loc-past", SELECT_ANCHOR, "\"line\": 101,\n        " + descOnly), 3,
                "inj:user-search-sql", "src/main/java/app/UserDao.java:101 - past the end of the file (100 lines)");
        failing("a trace line past the end of the file is refused", variant("loc-trace",
                "\"line\": 77,\n        \"scope\"", "\"line\": 500,\n        \"scope\""), 3, "inj:user-search-sql",
                "UserDao.java:500 - past the end of the file");
        failing("a file that is not in the repository is refused", variant("loc-nofile",
                "src/main/java/app/UserService.java", "src/main/java/app/Nope.java"), 3, "inj:user-search-sql",
                "Nope.java:40 - the file is not in the repository");

        Path two = variant("loc-two", SELECT_ANCHOR, "\"line\": 101,\n        " + descOnly);
        Path findings = two.resolve("findings.json");
        Files.writeString(findings, Files.readString(findings).replace("\"line\": 44,", "\"line\": 144,"));
        Path out = scratch.resolve("out-loc-two");
        Result both = runTool(two, out);
        check("every bad location is listed in one run", both.exit == 3 && both.stderr.contains("UserDao.java:101")
                && both.stderr.contains("SecurityConfig.java:144") && both.stderr.contains("cited location(s)")
                && !Files.exists(out.resolve("issues.csv")), both.stderr);

        Path repoDirs = scratch.resolve("repo-dir-as-file");
        Files.createDirectories(repoDirs.resolve("src/main/java/app/UserDao.java"));
        Result asDir = exec(List.of(java(), tool.toString(), fixtureOk.toString(), "--repo", repoDirs.toString(),
                "--out", scratch.resolve("out-loc-dir").toString()));
        check("a directory in place of a source file is refused", asDir.exit == 3
                && asDir.stderr.contains("not in the repository"), asDir.stderr);

        Result noRepoDir = exec(List.of(java(), tool.toString(), fixtureOk.toString(), "--repo",
                scratch.resolve("no-such-repo").toString(), "--out", scratch.resolve("out-loc-norepo").toString()));
        check("a repository directory that does not exist is refused", noRepoDir.exit == 3
                && noRepoDir.stderr.contains("repository not found"), noRepoDir.stderr);

        Path escape = scratch.resolve("repo-symlink");
        Files.createDirectories(escape.resolve("src/main/java/app"));
        try {
            Path outside = scratch.resolve("outside-source.java");
            Files.writeString(outside, "x\n".repeat(200));
            Files.createSymbolicLink(escape.resolve("src/main/java/app/UserDao.java"), outside);
            for (String other : List.of("OrderExportController", "OrderRepository", "UserController", "UserService",
                    "AdminController", "SecurityConfig")) {
                Files.copy(repoOk.resolve("src/main/java/app/" + other + ".java"),
                        escape.resolve("src/main/java/app/" + other + ".java"));
            }
            Result link = exec(List.of(java(), tool.toString(), fixtureOk.toString(), "--repo", escape.toString(),
                    "--out", scratch.resolve("out-loc-link").toString()));
            check("a symlink that leaves the repository is not accepted as a source file", link.exit == 3
                    && link.stderr.contains("UserDao.java") && link.stderr.contains("not in the repository"),
                    link.stderr);
        } catch (IOException | UnsupportedOperationException e) {
            skipped++;
            System.out.println("SKIP  symlink escape check: symbolic links cannot be created here (" + e + ")");
        }
    }

    private static void failing(String name, Path run, int exit, String fingerprint, String message) throws Exception {
        Path out = scratch.resolve("out-" + run.getFileName());
        Result r = runTool(run, out);
        check(name, r.exit == exit && r.stderr.contains(fingerprint) && r.stderr.contains(message)
                && !Files.exists(out.resolve("issues.csv")), "exit=" + r.exit + " stderr=" + r.stderr.strip());
    }

    // ------------------------------------------------------------------------------------------------------------
    // FindingsToCsv: empty result, usage
    // ------------------------------------------------------------------------------------------------------------

    private static void emptyFindings() throws Exception {
        Path run = variantWhole("empty", "[]");
        Path out = scratch.resolve("out-empty");
        Result r = runTool(run, out);
        check("no findings: exit 0", r.exit == 0, r.stderr);
        byte[] bytes = Files.readAllBytes(out.resolve("issues.csv"));
        String text = new String(bytes, StandardCharsets.UTF_8);
        check("no findings: header row only, with BOM and CRLF", text.startsWith("\uFEFFfile,line,title,")
                && text.endsWith(",generated_at\r\n") && text.indexOf("\r\n") == text.length() - 2, text);
        String[] last = r.stdout.strip().split("\\R");
        check("no findings: summary counts are zero", last[last.length - 1].contains("\"issues\":0")
                && last[last.length - 1].contains("\"rows\":0"), last[last.length - 1]);
    }

    private static void usageErrors() throws Exception {
        Result none = exec(List.of(java(), tool.toString()));
        check("no arguments: exit 2 with usage", none.exit == 2 && none.stderr.contains("usage:"));
        Result noOut = exec(List.of(java(), tool.toString(), fixtureOk.toString()));
        check("missing --out: exit 2", noOut.exit == 2 && noOut.stderr.contains("--out"));
        Result noRepo = exec(List.of(java(), tool.toString(), fixtureOk.toString(), "--out",
                scratch.resolve("out-norepo").toString()));
        check("missing --repo: exit 2 and nothing written", noRepo.exit == 2 && noRepo.stderr.contains("--repo")
                && !Files.exists(scratch.resolve("out-norepo/issues.csv")), noRepo.stderr);
    }

    // ------------------------------------------------------------------------------------------------------------
    // ValidateRun.java
    // ------------------------------------------------------------------------------------------------------------

    private static void validateRunChecks() throws Exception {
        Result ok = exec(List.of(java(), validateRun.toString(), fixtureOk.toString()));
        String okLast = lastLine(ok.stdout);
        check("valid run: exit 0 and valid=true", ok.exit == 0 && okLast.contains("\"valid\":true"), ok.stderr + okLast);
        check("valid run: no reduced mode on any operating system", !okLast.contains("weakened") && !okLast.contains("wrapper") && !okLast.contains("\"mode\""), okLast);

        Path unsorted = scratch.resolve("v-unsorted");
        Files.createDirectories(unsorted);
        Files.copy(fixtureOk.resolve("coverage-ledger.json"), unsorted.resolve("coverage-ledger.json"));
        String findings = Files.readString(fixtureOk.resolve("findings.json"));
        Files.writeString(unsorted.resolve("findings.json"), moveFirstFingerprintLast(findings));
        Result bad = exec(List.of(java(), validateRun.toString(), unsorted.toString()));
        check("rule violation fails with the rule's message, not a fallback", bad.exit == 1
                && bad.stderr.contains("sorted lexicographically") && lastLine(bad.stdout).contains("\"valid\":false"),
                bad.stderr);

        Path dir = scratch.resolve("v-dir");
        Files.createDirectories(dir.resolve("findings.json"));
        Files.copy(fixtureOk.resolve("coverage-ledger.json"), dir.resolve("coverage-ledger.json"));
        Result asDir = exec(List.of(java(), validateRun.toString(), dir.toString()));
        check("a directory in place of findings.json fails", asDir.exit == 1 && lastLine(asDir.stdout)
                .contains("\"findings\":{\"status\":\"fail\""), asDir.stderr);

        Path noLedger = scratch.resolve("v-noledger");
        Files.createDirectories(noLedger);
        Files.copy(fixtureOk.resolve("findings.json"), noLedger.resolve("findings.json"));
        Result missing = exec(List.of(java(), validateRun.toString(), noLedger.toString()));
        check("missing ledger fails", missing.exit == 1 && missing.stderr.contains("coverage-ledger.json not found"),
                missing.stderr);

        Path malformed = scratch.resolve("v-malformed");
        Files.createDirectories(malformed);
        Files.writeString(malformed.resolve("findings.json"), "[{\"a\":\"x\u202e\u001b[31mFORGED\"");
        Files.copy(fixtureOk.resolve("coverage-ledger.json"), malformed.resolve("coverage-ledger.json"));
        Result garbage = exec(List.of(java(), validateRun.toString(), malformed.toString()));
        check("malformed JSON fails and diagnostics hold no raw control characters", garbage.exit == 1
                && garbage.stderr.chars().noneMatch(c -> c == 0x1b || c == 0x202e), garbage.stderr);

        Result usage = exec(List.of(java(), validateRun.toString()));
        check("no run directory: exit 2", usage.exit == 2);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Every kit tool starts as a single source file
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Found by the first real run of the Black Duck flow on JDK 25: CodeGraph.java and DependencyAdvisor.java declared a package, which the single-file
     * launcher of newer JDKs refuses ("end of path to source file does not match its package name"), so the advisor could not scan. Do not add one back.
     */
    private static void everyToolLaunches() throws Exception {
        for (String name : List.of("CodeGraph", "DependencyAdvisor", "FindingsToCsv", "IssueList", "ValidateRun")) {
            Path source = kit.resolve(".github/appmod/tools/" + name + ".java");
            Result r = exec(List.of(java(), source.toString()));
            String all = r.stdout + r.stderr;
            check(name + ".java starts as a single source file on this JDK and says how to use it", !all.contains("does not match its package name")
                    && !all.contains("compilation failed") && all.toLowerCase().contains("usage"), all);
        }
    }

    private static String moveFirstFingerprintLast(String findings) {
        // The fixture is sorted by fingerprint; giving the first record the last fingerprint breaks that rule.
        return findings.replace("\"auth:admin-reset-unauthenticated\"", "\"zzz:moved-last\"");
    }

    // ------------------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------------------

    private record Result(int exit, String stdout, String stderr) {}

    private static Result runTool(Path run, Path out) throws Exception {
        return runTool(run, repoOk, out);
    }

    private static Result runTool(Path run, Path repo, Path out) throws Exception {
        return exec(List.of(java(), tool.toString(), run.toString(), "--repo", repo.toString(), "--out",
                out.toString(), "--generated-at", "2026-11-10T12:00:00Z"));
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static Result exec(List<String> command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
        var out = new String[1];
        var err = new String[1];
        Thread t1 = new Thread(() -> out[0] = read(p.getInputStream()));
        Thread t2 = new Thread(() -> err[0] = read(p.getErrorStream()));
        t1.start();
        t2.start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out: " + command);
        }
        t1.join();
        t2.join();
        return new Result(p.exitValue(), out[0], err[0]);
    }

    private static String read(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Copies the fixture run to a new directory, replacing one piece of findings.json text (must occur once). */
    private static Path variant(String name, String from, String to) throws IOException {
        Path run = scratch.resolve("run-" + name);
        Files.createDirectories(run);
        String findings = Files.readString(fixtureOk.resolve("findings.json"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        int first = findings.indexOf(from);
        if (first < 0 || findings.indexOf(from, first + 1) >= 0) {
            throw new IllegalStateException("fixture text for variant '" + name + "' must occur exactly once: " + from);
        }
        Files.writeString(run.resolve("findings.json"), findings.replace(from, to), StandardCharsets.UTF_8);
        Files.copy(fixtureOk.resolve("run-metadata.json"), run.resolve("run-metadata.json"));
        Files.copy(fixtureOk.resolve("coverage-ledger.json"), run.resolve("coverage-ledger.json"));
        return run;
    }

    private static Path variantWhole(String name, String findings) throws IOException {
        Path run = scratch.resolve("run-" + name);
        Files.createDirectories(run);
        Files.writeString(run.resolve("findings.json"), findings, StandardCharsets.UTF_8);
        return run;
    }

    private static String lastLine(String text) {
        String[] lines = text.strip().split("\\R");
        return lines[lines.length - 1];
    }

    private static List<String> listNames(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "\n        " + detail.strip()));
        }
    }

    /** Independent RFC 4180 reader: quoted fields, doubled quotes, CRLF or LF records, optional leading BOM. */
    private static List<List<String>> parse(String text) {
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (quoted) throw new IllegalStateException("unterminated quoted field");
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
