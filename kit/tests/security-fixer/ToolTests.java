import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Checks for IssueList.java, and for the security-fixer agent and prompt files. Single file, JDK only; the tool runs as a separate
 * process, as users run it.
 *
 * <pre>
 *   java tests/security-fixer/ToolTests.java [kit-root]        (kit-root defaults to the current directory)
 * </pre>
 *
 * Exit code 0 when every check passes, 1 otherwise.
 */
public final class ToolTests {

    private static final String HEADER = "file,line,title,fingerprint,verdict,severity,confidence,location_note,description,root_cause,"
            + "trace,remediation,blockers,validation_plan,attack_class,commit,generated_at";
    private static final String SHA = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432";

    private static int passed;
    private static int failed;
    private static int skipped;
    private static Path kit;
    private static Path tool;
    private static Path scratch;

    public static void main(String[] args) throws Exception {
        kit = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        tool = kit.resolve(".github/appmod/tools/IssueList.java");
        scratch = Files.createTempDirectory("security-fixer-tests-");
        try {
            order();
            auditedCommit();
            quotingAndGuard();
            json();
            failures();
            usage();
            largeField();
            roundTripWithTheAuditWriter();
            worktreeCommands();
            reportingSkill();
            routingAndDocs();
            agentFiles();
            acceptanceCheckerTestsItself();
        } finally {
            deleteTree(scratch);
        }
        System.out.printf("%n%d passed, %d failed, %d skipped%n", passed, failed, skipped);
        System.exit(failed == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The acceptance checker, tested against a correct fix and against fixes with a part left out (no agent involved)
    // ------------------------------------------------------------------------------------------------------------

    private static void acceptanceCheckerTestsItself() throws Exception {
        Path fixtureDir = kit.resolve("tests/security-fixer/fixture");
        if (!Files.exists(fixtureDir.resolve("BuildFixture.java"))) {
            skip("the acceptance checker against a reference fix (the fixture builder is not in this kit)");
            return;
        }
        record Case(String name, String skip, boolean passes, String failing) {
        }
        for (Case c : List.of(new Case("a correct fix", null, true, ""),
                new Case("a fix without the token commit", "token", false, "A3 five fix commits on the branch"),
                new Case("a fix without result.json", "result", false, "A10 result.json: status succeeded"),
                new Case("a fix without summary.md", "summary", false, "A10 summary.md exists"))) {
            Path target = scratch.resolve("fixture-" + (c.skip == null ? "good" : c.skip));
            Result built = exec(List.of(java(), fixtureDir.resolve("BuildFixture.java").toString(), target.toString(), "--tool", "copilot"));
            check("the fixture builds (" + c.name + ")", built.exit == 0 && built.stdout.contains("5 confirmed, 1 needs_validation"), built.stderr + built.stdout);
            List<String> fix = new java.util.ArrayList<>(List.of(java(), fixtureDir.resolve("ReferenceFix.java").toString(), target.resolve("shop").toString()));
            if (c.skip != null) {
                fix.addAll(List.of("--skip", c.skip));
            }
            Result applied = exec(fix);
            check("the reference fix is applied (" + c.name + ")", applied.exit == 0, applied.stderr + applied.stdout);
            Result checked = exec(List.of(java(), fixtureDir.resolve("CheckAcceptance.java").toString(), target.resolve("shop").toString()));
            if (c.passes) {
                check("the checker passes 37 of 37 on " + c.name, checked.exit == 0 && checked.stdout.contains("37 passed, 0 failed"), checked.stdout);
            } else {
                check("the checker fails on " + c.name + ", at " + c.failing, checked.exit == 1 && checked.stdout.contains("FAIL  " + c.failing), checked.stdout);
            }
        }
        Path unfixed = scratch.resolve("fixture-unfixed");
        exec(List.of(java(), fixtureDir.resolve("BuildFixture.java").toString(), unfixed.toString(), "--tool", "copilot"));
        Result none = exec(List.of(java(), fixtureDir.resolve("CheckAcceptance.java").toString(), unfixed.resolve("shop").toString()));
        check("the checker fails on a fixture nobody fixed (no worktree: A1)", none.exit == 1 && none.stdout.contains("FAIL  A1 a worktree exists next to the checkout"), none.stdout);
    }

    // ------------------------------------------------------------------------------------------------------------
    // IssueList: order, grouping, text

    private static String row(String file, int line, String title, String fingerprint, String verdict, String severity,
            String description) {
        return "%s,%d,%s,%s,%s,%s,high,,%s,cause,,fix it,,,,%s,2026-11-10T12:00:00Z".formatted(file, line, title, fingerprint, verdict,
                severity, description, SHA);
    }

    private static Path csv(String name, String... lines) throws IOException {
        return Files.writeString(scratch.resolve(name), "﻿" + String.join("\r\n", lines) + "\r\n", StandardCharsets.UTF_8);
    }

    private static void order() throws Exception {
        Path file = csv("order.csv", HEADER,
                row("src/b.js", 10, "Medium issue", "m:1", "confirmed", "medium", "plain"),
                row("src/a.js", 3, "Critical issue", "c:1", "confirmed", "critical", "plain"),
                row("src/d.js", 7, "Unproven issue", "u:1", "needs_validation", "", "plain"),
                row("src/a.js", 30, "Critical issue", "c:1", "confirmed", "critical", "plain"),
                row("src/h.js", 1, "High issue", "h:1", "confirmed", "high", "plain"),
                row("src/c.js", 5, "Critical issue", "c:1", "confirmed", "critical", "plain"));
        Result r = run(file.toString());
        check("a valid audit CSV exits 0", r.exit == 0, r.stderr);
        List<String> lines = r.stdout.lines().toList();
        check("the heading counts issues, locations, verdicts and names the audited commit",
                lines.getFirst().equals("4 issues, 6 locations (3 confirmed, 1 needs_validation); audited commit " + SHA), lines.getFirst());
        List<String> titles = lines.stream().filter(line -> line.matches("\\d+\\. \\[.*")).toList();
        check("order: critical, high, medium, then the unproven issue",
                titles.size() == 4 && titles.get(0).contains("[critical/confirmed] Critical issue")
                        && titles.get(1).contains("[high/confirmed] High issue") && titles.get(2).contains("[medium/confirmed] Medium issue")
                        && titles.get(3).contains("[needs_validation] Unproven issue"), titles.toString());
        check("rows with one fingerprint are one issue with all its locations, in file order of the rows",
                r.stdout.contains("locations: src/a.js:3, src/a.js:30, src/c.js:5"));
        check("an issue is listed once", r.stdout.split("Critical issue").length - 1 == 1);
        Path again = csv("order-same-severity.csv", HEADER,
                row("src/x.js", 1, "Second", "s:2", "confirmed", "high", "plain"),
                row("src/y.js", 1, "First", "s:1", "confirmed", "high", "plain"));
        List<String> same = run(again.toString()).stdout.lines().filter(line -> line.matches("\\d+\\. \\[.*")).toList();
        check("equal severity keeps the order of first appearance", same.get(0).contains("Second") && same.get(1).contains("First"));
        Path odd = csv("order-verdict-first.csv", HEADER,
                row("src/u.js", 1, "Unproven but severe", "u:9", "needs_validation", "critical", "plain"),
                row("src/k.js", 1, "Confirmed but minor", "k:9", "confirmed", "low", "plain"));
        List<String> oddTitles = run(odd.toString()).stdout.lines().filter(line -> line.matches("\\d+\\. \\[.*")).toList();
        check("a confirmed issue comes before an unproven one whatever their severities",
                oddTitles.get(0).contains("Confirmed but minor") && oddTitles.get(1).contains("Unproven but severe"), oddTitles.toString());
    }

    private static void auditedCommit() throws Exception {
        String clean = "\"commit=" + SHA + ", dirty=false\"";
        String dirty = "\"commit=" + SHA + ", dirty=true\"";
        for (var variant : List.of(
                new String[] {"bare id", SHA, "audited commit " + SHA + "\n", "\"dirty\":null"},
                new String[] {"id and clean flag as the audit tool writes it", clean, "audited commit " + SHA + " (clean working tree)", "\"dirty\":false"},
                new String[] {"id and dirty flag", dirty, "audited commit " + SHA + " (the audited working tree had uncommitted changes)", "\"dirty\":true"},
                new String[] {"something that is not a commit", "ref=main", "audited commit ref=main", "\"commit\":\"ref=main\""})) {
            String row = "src/a.js,3,t,f:1,confirmed,high,high,,d,c,,fix,,,," + variant[1] + ",2026-11-10T12:00:00Z";
            Path file = Files.writeString(scratch.resolve("commit-" + variant[0].replace(' ', '-') + ".csv"),
                    "\uFEFF" + HEADER + "\r\n" + row + "\r\n", StandardCharsets.UTF_8);
            Result text = run(file.toString());
            Result json = run(file.toString(), "--json");
            check("the audited commit is shown for: " + variant[0], text.exit == 0 && (text.stdout.lines().findFirst().orElse("") + "\n")
                    .contains(variant[2].stripTrailing()), text.stdout.lines().findFirst().orElse(""));
            check("--json carries commit and dirty for: " + variant[0], json.exit == 0 && json.stdout.contains(variant[3]), json.stdout);
        }
        check("the id is what --json calls the commit when the cell holds the labelled form",
                run(Files.writeString(scratch.resolve("commit-json.csv"), "\uFEFF" + HEADER + "\r\nsrc/a.js,3,t,f:1,confirmed,high,high,,d,c,,fix,,,,"
                        + clean + ",2026-11-10T12:00:00Z\r\n", StandardCharsets.UTF_8).toString(), "--json").stdout
                        .contains("\"commit\":\"" + SHA + "\""));
    }

    private static void quotingAndGuard() throws Exception {
        String tricky = "\"The term, with a comma, is \"\"quoted\"\".\nSecond line with Café and 管理.\"";
        Path file = csv("tricky.csv", HEADER,
                row("src/a.js", 3, "\"Title, with comma\"", "t:1", "confirmed", "critical", tricky),
                row("src/b.js", 4, "Guarded", "g:1", "confirmed", "high", "'=cmd|' /C calc'!A0"),
                row("src/c.js", 5, "Not guarded", "g:2", "confirmed", "low", "'quoted word' stays"));
        String out = run(file.toString()).stdout;
        check("a quoted title with a comma stays one title", out.contains("[critical/confirmed] Title, with comma  (t:1)"));
        check("commas, doubled quotes and a line break inside one cell stay in one value",
                out.contains("description: The term, with a comma, is \"quoted\".\n      Second line with Café and 管理."), out);
        check("the guard quote in front of a formula-looking cell is taken off", out.contains("description: =cmd|' /C calc'!A0"), out);
        check("a quote that guards nothing is kept", out.contains("description: 'quoted word' stays"), out);
        check("a file without the byte order mark reads the same",
                run(Files.writeString(scratch.resolve("nobom.csv"), String.join("\n", HEADER,
                        row("src/a.js", 3, "T", "n:1", "confirmed", "high", "plain")), StandardCharsets.UTF_8).toString()).exit == 0);
    }

    private static void json() throws Exception {
        Path file = csv("json.csv", HEADER,
                row("src/a.js", 3, "\"Critical \"\"issue\"\"\"", "c:1", "confirmed", "critical", "\"line one\nline two\ttab\""),
                row("src/u.js", 9, "Unproven", "u:1", "needs_validation", "", "plain"));
        Result r = run(file.toString(), "--json");
        check("--json exits 0", r.exit == 0, r.stderr);
        check("--json prints one JSON document on one line", r.stdout.strip().lines().count() == 1 && r.stdout.startsWith("{\"file\":"));
        check("--json carries the commit, the row count and both issues in order",
                r.stdout.contains("\"commit\":\"" + SHA + "\"") && r.stdout.contains("\"rows\":2")
                        && r.stdout.indexOf("\"fingerprint\":\"c:1\"") < r.stdout.indexOf("\"fingerprint\":\"u:1\""));
        check("--json escapes quotes, line breaks and tabs", r.stdout.contains("\\\"issue\\\"") && r.stdout.contains("line one\\nline two\\ttab"));
        try {
            java.util.Map<?, ?> document = (java.util.Map<?, ?>) new MiniJson(r.stdout).document();
            java.util.List<?> issues = (java.util.List<?>) document.get("issues");
            java.util.Map<?, ?> first = (java.util.Map<?, ?>) issues.get(0);
            java.util.Map<?, ?> location = (java.util.Map<?, ?>) ((java.util.List<?>) first.get("locations")).get(0);
            check("--json is valid JSON with the expected content", issues.size() == 2 && Double.valueOf(3).equals(location.get("line"))
                    && "Critical \"issue\"".equals(first.get("title")));
        } catch (RuntimeException e) {
            check("--json is valid JSON with the expected content", false, e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // IssueList: what is refused

    private static void refused(String name, Path file, String... messageParts) throws Exception {
        Result r = run(file.toString());
        boolean mentions = Stream.of(messageParts).allMatch(part -> r.stderr.contains(part));
        check(name + ": exit 2, prints no issue, names the problem",
                r.exit == 2 && r.stdout.isEmpty() && mentions, "exit=%d stdout=[%s] stderr=[%s]".formatted(r.exit, r.stdout, r.stderr));
    }

    private static void failures() throws Exception {
        refused("a missing column", csv("nocolumn.csv", HEADER.replace(",verdict", ""), "src/a.js,3,t,f,high,x"), "column verdict is missing");
        refused("several missing columns", csv("nocols.csv", "a,b,c", "1,2,3"), "columns file, line, fingerprint, verdict are missing");
        refused("a line that is not a number", csv("badline.csv", HEADER, row("src/a.js", 1, "t", "f:1", "confirmed", "high", "x")
                .replace("src/a.js,1,", "src/a.js,abc,")), "row 2", "'abc'");
        refused("a zero line", csv("zeroline.csv", HEADER, row("src/a.js", 0, "t", "f:1", "confirmed", "high", "x")), "row 2", "'0'");
        refused("an empty file", Files.writeString(scratch.resolve("empty.csv"), ""), "the file is empty");
        refused("a header and no rows", csv("headeronly.csv", HEADER), "no rows");
        refused("a JSON file that is not a CSV", Files.writeString(scratch.resolve("notcsv.csv"), "{\"issues\": [1, 2, 3]}"),
                "quote in the middle of a cell");
        refused("a text file that is not a CSV", Files.writeString(scratch.resolve("words.csv"), "just some words\nand some more\n"),
                "not an audit CSV");
        refused("an unknown verdict", csv("verdict.csv", HEADER, row("src/a.js", 1, "t", "f:1", "probably", "high", "x")),
                "row 2", "the verdict 'probably'");
        refused("an unknown severity", csv("severity.csv", HEADER, row("src/a.js", 1, "t", "f:1", "confirmed", "urgent", "x")),
                "row 2", "the severity 'urgent'");
        refused("a row with the wrong number of cells", csv("cells.csv", HEADER, "src/a.js,1,t,f:1,confirmed"), "row 2 has 5 cells but the header has 17");
        refused("rows of one issue that disagree about the verdict", csv("disagree.csv", HEADER,
                row("src/a.js", 1, "t", "f:1", "confirmed", "high", "x"), row("src/b.js", 2, "t", "f:1", "needs_validation", "", "x")),
                "f:1", "disagree");
        refused("a quoted cell that never closes", csv("unclosed.csv", HEADER, row("src/a.js", 1, "t", "f:1", "confirmed", "high",
                "\"never closed")), "never closed");
        refused("a quote in the middle of a cell", csv("midquote.csv", HEADER, row("src/a.js", 1, "t", "f:1", "confirmed", "high",
                "mid\"quote")), "quote in the middle");
        refused("a row without a fingerprint", csv("nofp.csv", HEADER, row("src/a.js", 1, "t", "", "confirmed", "high", "x")), "no fingerprint");
        refused("a file that does not exist", scratch.resolve("missing.csv"), "missing.csv");
        Path hostile = csv("hostile-verdict.csv", HEADER, row("src/a.js", 1, "t", "f:1", "bad\u001b[31m", "high", "x"));
        Result r = run(hostile.toString());
        check("a control character in a rejected value is not echoed", r.exit == 2 && !r.stderr.contains("\u001b"), r.stderr);
        // Nothing may be printed as if valid when any row is bad, even when earlier rows are fine.
        Path late = csv("late-error.csv", HEADER, row("src/a.js", 1, "Fine", "f:1", "confirmed", "high", "x"),
                row("src/b.js", 2, "Bad", "f:2", "confirmed", "high", "x").replace("src/b.js,2,", "src/b.js,two,"));
        Result lateResult = run(late.toString());
        check("an error in a late row prints no issue at all", lateResult.exit == 2 && lateResult.stdout.isEmpty(), lateResult.stdout);
    }

    private static void usage() throws Exception {
        for (List<String> args : List.of(List.<String>of(), List.of("--json"), List.of("a.csv", "--wat"), List.of("a.csv", "b.csv", "c.csv"))) {
            Result r = exec(Stream.concat(Stream.of(java(), tool.toString()), args.stream()).toList());
            check("usage error for " + args + ": exit 2 and the usage line", r.exit == 2 && r.stderr.contains("usage: java IssueList.java")
                    && r.stdout.isEmpty(), r.stderr);
        }
    }

    private static void largeField() throws Exception {
        String big = "x".repeat(25_000);
        Result r = run(csv("big.csv", HEADER, row("src/a.js", 1, "t", "f:1", "confirmed", "high", big)).toString());
        check("a very large cell is cut with a marker instead of failing", r.exit == 0 && r.stdout.contains("[cut: 5000 more characters]")
                && r.stdout.length() < 22_000, "exit=%d length=%d".formatted(r.exit, r.stdout.length()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // IssueList reads what the audit tool writes

    private static void roundTripWithTheAuditWriter() throws Exception {
        Path writer = kit.resolve(".github/appmod/tools/FindingsToCsv.java");
        Path run = kit.resolve("tests/security-audit/fixtures/run-ok");
        Path repo = kit.resolve("tests/security-audit/fixtures/repo-ok");
        if (!Files.exists(writer) || !Files.isDirectory(run)) {
            skip("round trip with FindingsToCsv (the audit tool or its fixture is not in this kit)");
            return;
        }
        Path out = scratch.resolve("audit-out");
        Result written = exec(List.of(java(), writer.toString(), run.toString(), "--repo", repo.toString(), "--out", out.toString(),
                "--generated-at", "2026-11-10T12:00:00Z"));
        check("the audit writer produces the fixture CSV", written.exit == 0, written.stderr);
        Result r = run(out.resolve("issues.csv").toString());
        check("IssueList reads the audit writer's CSV", r.exit == 0, r.stderr);
        String heading = r.stdout.lines().findFirst().orElse("");
        check("every issue and location of the fixture comes back: 3 issues, 4 locations, 2 confirmed and 1 unproven",
                heading.startsWith("3 issues, 4 locations (2 confirmed, 1 needs_validation)"), heading);
        List<String> titles = r.stdout.lines().filter(line -> line.matches("\\d+\\. \\[.*")).toList();
        check("the audit's order is kept: critical first, the unproven issue last",
                titles.getFirst().contains("[critical/confirmed]") && titles.getLast().contains("[needs_validation]"), titles.toString());
        check("the fixture's multi-line, non-ASCII description comes back intact",
                r.stdout.contains("The reset route is registered outside the security filter chain.")
                        && r.stdout.contains("Café-Nutzer sind betroffen: Zugriff über 管理 Konsole."), r.stdout);
    }

    // ------------------------------------------------------------------------------------------------------------
    // The git commands the agent file prescribes, run in a scratch repository

    private static Result git(Path folder, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", folder.toString(), "-c", "user.email=t@example.com", "-c",
                "user.name=t", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        return exec(command);
    }

    private static String out(Path folder, String... args) throws Exception {
        Result r = git(folder, args);
        if (r.exit != 0) {
            throw new IllegalStateException("git %s failed: %s".formatted(String.join(" ", args), r.stderr));
        }
        return r.stdout.strip();
    }

    private static Path realDir(Path folder, String printed) throws IOException {
        return folder.resolve(printed).toRealPath();
    }

    private static void worktreeCommands() throws Exception {
        Path repo = Files.createDirectories(scratch.resolve("wt-repo"));
        out(repo, "init", "-q");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        out(repo, "add", "a.txt");
        out(repo, "commit", "-q", "-m", "first");
        String audited = out(repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        out(repo, "commit", "-q", "-am", "second");
        String head = out(repo, "rev-parse", "HEAD");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nuncommitted\n");
        String branchBefore = out(repo, "rev-parse", "--abbrev-ref", "HEAD");
        String statusBefore = out(repo, "status", "--porcelain");

        check("in a primary checkout the git dir and the common dir are the same",
                realDir(repo, out(repo, "rev-parse", "--git-dir")).equals(realDir(repo, out(repo, "rev-parse", "--git-common-dir"))));
        check("the audited commit is found with cat-file", git(repo, "cat-file", "-e", audited + "^{commit}").exit == 0);
        check("a commit that is not in the repository is not found",
                git(repo, "cat-file", "-e", "1234567890abcdef1234567890abcdef12345678^{commit}").exit != 0);

        Path folder = scratch.resolve("wt-repo-fix-ab12cd34");
        Result added = git(repo, "worktree", "add", "-b", "appmod/fix-ab12cd34", folder.toString(), audited);
        check("git worktree add -b appmod/fix-<id8> <folder> <base> creates the worktree", added.exit == 0 && Files.isDirectory(folder), added.stderr);
        check("in the linked worktree the git dir and the common dir differ",
                !realDir(folder, out(folder, "rev-parse", "--git-dir")).equals(realDir(folder, out(folder, "rev-parse", "--git-common-dir"))));
        check("the worktree is on its own branch, started from the audited commit",
                out(folder, "rev-parse", "--abbrev-ref", "HEAD").equals("appmod/fix-ab12cd34") && out(folder, "rev-parse", "HEAD").equals(audited));
        check("the worktree holds the audited code, not the later change", Files.readString(folder.resolve("a.txt")).replace("\r\n", "\n")
                .equals("one\n"));
        check("the person's checkout is untouched: branch, HEAD, status and the uncommitted edit",
                out(repo, "rev-parse", "--abbrev-ref", "HEAD").equals(branchBefore) && out(repo, "rev-parse", "HEAD").equals(head)
                        && out(repo, "status", "--porcelain").equals(statusBefore)
                        && Files.readString(repo.resolve("a.txt")).replace("\r\n", "\n").equals("one\ntwo\nuncommitted\n"));
        check("a commit made in the worktree is on its branch only", git(folder, "commit", "--allow-empty", "-q", "-m", "fix(security): demo [d:1]").exit == 0
                && out(repo, "rev-parse", "HEAD").equals(head) && out(folder, "rev-list", "--count", audited + "..appmod/fix-ab12cd34").equals("1"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // The reporting skill, the harness routing and the documentation

    private static void reportingSkill() throws Exception {
        String skill = Files.readString(kit.resolve(".github/skills/harness-reporting/SKILL.md")).replace("\r\n", "\n");
        int start = skill.indexOf("```json");
        int end = skill.indexOf("```", start + 7);
        String block = skill.substring(start + 7, end);
        check("the result.json example holds issuesFixed and issuesLeft", block.contains("\"issuesFixed\"") && block.contains("\"issuesLeft\"")
                && block.contains("\"fingerprint\""));
        try {
            new MiniJson(block).document();
            check("the result.json example is valid JSON", true);
        } catch (RuntimeException e) {
            check("the result.json example is valid JSON", false, e.toString());
        }
        for (String status : List.of("succeeded", "partially_succeeded", "needs_input", "failed")) {
            check("the skill explains the status " + status + " for a fix job", skill.contains("| `" + status + "` |"));
        }
        check("the skill names the four engine states", skill.contains("DONE") && skill.contains("PARTIAL") && skill.contains("NEEDS_INPUT")
                && skill.contains("ERROR"));
    }

    private static void routingAndDocs() throws Exception {
        String harness = Files.readString(kit.resolve(".github/agents/appmod-harness.agent.md")).replace("\r\n", "\n");
        check("the routing table has a row that sends fix requests to security-fixer", harness.lines().anyMatch(line -> line.startsWith("|")
                && line.contains("`security-fixer`") && line.contains("Fix the findings of a security audit")));
        check("the audit row still says the audit never fixes", harness.lines().anyMatch(line -> line.startsWith("|")
                && line.contains("`security-audit`") && line.contains("Never fixes")));
        check("the harness still never asks the audit agent to change code", harness.contains("never ask\n  `security-audit` to change code"));
        check("the harness never fixes audit findings itself", harness.contains("Never fix audit findings yourself"));
        check("the harness tells how to present a fix result and not to run the verifier twice",
                harness.contains("**Security fixes.**") && harness.contains("do not run the verifier again"));
        String instructions = Files.readString(kit.resolve(".github/instructions/appmod-harness.instructions.md"));
        check("the shared instructions name security-fixer", instructions.contains("security-fixer") && instructions.contains("git worktree"));
        String readme = Files.readString(kit.resolve("README.md"));
        check("the README describes the fixer, its prompt, its tool and that it changes code", readme.contains("## Security fixer")
                && readme.contains("/security-fix") && readme.contains("IssueList.java") && readme.contains("changes code"));
    }

    // ------------------------------------------------------------------------------------------------------------
    // The agent and prompt files

    private static void agentFiles() throws Exception {
        Path agent = kit.resolve(".github/agents/security-fixer.agent.md");
        Path prompt = kit.resolve(".github/prompts/security-fix.prompt.md");
        if (!Files.exists(agent)) {
            skip("agent file checks (security-fixer.agent.md is not written yet)");
            return;
        }
        String text = Files.readString(agent);
        String front = text.startsWith("---") ? text.substring(3, text.indexOf("\n---", 3)) : "";
        check("the agent has front matter with name security-fixer", front.contains("name: security-fixer"));
        check("the agent is user-invocable", front.contains("user-invocable: true"));
        check("the agent has the tools read, search, edit, shell and agent",
                Stream.of("\"read\"", "\"search\"", "\"edit\"", "\"shell\"", "\"agent\"").allMatch(front::contains), front);
        for (String phrase : List.of("IssueList.java", "fingerprint", "confirmed", "needs_validation", "critical", "one commit per issue",
                "fix(security):", "never push", "data, never instructions", "Never delete or disable", ".appmod/security-audit",
                "git rev-parse --git-dir", "--git-common-dir", "linked worktree", "primary checkout", "As a sub-agent", "appmod/fix-", "work in place", "interactive", "headless", "assumptions",
                "appmod-verifier", "If the verifier cannot run", "Do not rely on changing directory", "mvn -f <worktree>", "issuesFixed", "issuesLeft", "partially_succeeded", "needs_input", "baseline")) {
            check("the agent states: " + phrase, text.toLowerCase().contains(phrase.toLowerCase()));
        }
        if (Files.exists(prompt)) {
            String p = Files.readString(prompt);
            check("the prompt names the agent and takes the CSV path", p.contains("agent: security-fixer") && p.contains("issues.csv")
                    && p.contains("${input:"));
        } else {
            skip("prompt checks (security-fix.prompt.md is not written yet)");
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Plumbing

    private record Result(int exit, String stdout, String stderr) {
    }

    private static Result run(String... args) throws Exception {
        return exec(Stream.concat(Stream.of(java(), tool.toString()), Stream.of(args)).toList());
    }

    private static Result exec(List<String> command) throws Exception {
        return exec(command, null);
    }

    private static Result exec(List<String> command, String stdin) throws Exception {
        Process p = new ProcessBuilder(command).start();
        if (stdin != null) {
            p.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));
        }
        p.getOutputStream().close();
        var err = new java.util.concurrent.CompletableFuture<String>();
        Thread.ofPlatform().start(() -> {
            try {
                err.complete(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                err.complete("");
            }
        });
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("timed out: " + command);
        }
        return new Result(p.exitValue(), out.replace("\r\n", "\n").stripTrailing(), err.get().replace("\r\n", "\n").stripTrailing());
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** A strict JSON reader for the checks above: objects, arrays, strings, numbers, booleans and null; it throws on anything else. */
    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) {
            this.s = s;
        }

        Object document() {
            ws();
            Object value = value();
            ws();
            if (i != s.length()) {
                throw new IllegalArgumentException("text after the value at " + i);
            }
            return value;
        }

        private void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) {
                i++;
            }
        }

        private Object value() {
            char c = i < s.length() ? s.charAt(i) : '?';
            if (c == '{') {
                java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
                i++;
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return map;
                }
                while (true) {
                    ws();
                    String key = string();
                    ws();
                    expect(':');
                    ws();
                    map.put(key, value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect('}');
                    return map;
                }
            }
            if (c == '[') {
                java.util.List<Object> list = new java.util.ArrayList<>();
                i++;
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    ws();
                    list.add(value());
                    ws();
                    if (s.charAt(i) == ',') {
                        i++;
                        continue;
                    }
                    expect(']');
                    return list;
                }
            }
            if (c == '"') {
                return string();
            }
            for (String word : List.of("true", "false", "null")) {
                if (s.startsWith(word, i)) {
                    i += word.length();
                    return word.equals("null") ? null : Boolean.valueOf(word);
                }
            }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("not a value at " + i);
            }
            return Double.valueOf(s.substring(start, i));
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i++) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw new IllegalArgumentException("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw new IllegalArgumentException("control character in a string at " + (i - 1));
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    case '"', '\\', '/' -> out.append(e);
                    default -> throw new IllegalArgumentException("bad escape \\" + e);
                }
            }
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  ok    " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + (detail.isEmpty() ? "" : "\n        " + detail.replace("\n", "\n        ")));
        }
    }

    private static void skip(String name) {
        skipped++;
        System.out.println("  skip  " + name);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                // Git's object files are read-only on Windows: clear the flag first, and never let cleanup fail the run.
                path.toFile().setWritable(true);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    System.out.println("  note  could not remove " + path + ": " + e.getMessage());
                }
            }
        }
    }
}
