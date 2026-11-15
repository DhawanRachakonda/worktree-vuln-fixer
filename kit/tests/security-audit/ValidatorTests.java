import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Tests of the security-audit validators (ValidateFindings.java, ValidateCoverageLedger.java, ValidateRun.java and their shared Json.java).
 * Single file, JDK 25 only: {@code java tests/security-audit/ValidatorTests.java}. Exit code 0 when every check passes, 1 otherwise.
 *
 * <p>The validators are ports of the vendored JavaScript validators of the upstream skill. The golden cases ({@code validators/*.json}) were
 * recorded from the output of those originals (exit code, standard output, standard error) on the upstream test inputs, hand-made edge inputs and
 * mutated inputs chosen so that every distinct message is covered; this program runs the ports on the same inputs and requires identical output.
 */
public final class ValidatorTests {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    private static Path kit;
    private static Path skill;
    private static Path tools;
    private static Path temp;
    private static ClassLoader loader;

    private ValidatorTests() {
    }

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            System.out.println("FAIL  " + name + (detail == null || detail.isEmpty() ? "" : "  <- " + detail));
            failures.add(name);
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    public static void main(String[] args) throws Exception {
        kit = locateKit(args);
        skill = kit.resolve(".github/skills/security-audit");
        tools = kit.resolve(".github/appmod/tools");
        temp = Files.createTempDirectory("validator-tests-");
        try {
            compile();
            goldenCases("findings", "findings-cases.json");
            goldenCases("ledger", "ledger-cases.json");
            specialInputs();
            helpers();
            commandLine();
            validateRun();
        } finally {
            deleteTree(temp);
        }
        System.out.println();
        System.out.println(checks + " checks, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println("  failed: " + f));
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------------------------------

    private static Path locateKit(String[] args) {
        if (args.length > 0) {
            return Path.of(args[0]).toAbsolutePath().normalize();
        }
        String command = System.getProperty("sun.java.command", "");
        int launcher = command.indexOf("SourceLauncher ");
        if (launcher >= 0) {
            String rest = command.substring(launcher + "SourceLauncher ".length());
            int end = rest.indexOf(".java");
            if (end > 0) {
                return Path.of(rest.substring(0, end + 5)).toAbsolutePath().getParent().getParent().getParent();
            }
        }
        return Path.of("").toAbsolutePath();
    }

    /** The validators are compiled in this process, so that thousands of cases do not need a JVM each. */
    private static void compile() throws Exception {
        Path out = Files.createDirectories(temp.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int code = compiler.run(null, null, null, "-d", out.toString(), skill.resolve("Json.java").toString(),
                skill.resolve("ValidateFindings.java").toString(), skill.resolve("ValidateCoverageLedger.java").toString());
        check("the three validator sources compile together", code == 0);
        if (code != 0) {
            throw new IllegalStateException("cannot continue without the validators");
        }
        loader = new URLClassLoader(new URL[] {out.toUri().toURL()}, ValidatorTests.class.getClassLoader());
    }

    private record Outcome(int code, String out, String err) {
    }

    private static Method method(String type, String name, Class<?>... parameters) throws Exception {
        Method m = loader.loadClass(type).getDeclaredMethod(name, parameters);
        m.setAccessible(true);
        return m;
    }

    private static Outcome run(String kind, String... args) throws Exception {
        Method run = method(kind.equals("findings") ? "ValidateFindings" : "ValidateCoverageLedger", "run", String[].class, PrintStream.class,
                PrintStream.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8); PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = (Integer) run.invoke(null, (Object) args, o, e);
        }
        return new Outcome(code, normal(out.toString(StandardCharsets.UTF_8)), normal(err.toString(StandardCharsets.UTF_8)));
    }

    private static String normal(String text) {
        return text.replace("\r\n", "\n");
    }

    private static Outcome runFile(String kind, Path file) throws Exception {
        return kind.equals("findings")
                ? run(kind, "--schema", skill.resolve("report-schema.json").toString(), file.toString())
                : run(kind, file.toString());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Golden cases
    // ---------------------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void goldenCases(String kind, String file) throws Exception {
        String text = Files.readString(tools.getParent().getParent().getParent().resolve("tests/security-audit/validators").resolve(file));
        List<Object> cases = (List<Object>) method("Json", "parse", String.class).invoke(null, text);
        int same = 0;
        List<String> different = new ArrayList<>();
        Path input = temp.resolve("golden-" + kind + ".json");
        for (Object raw : cases) {
            Map<String, Object> c = (Map<String, Object>) raw;
            Files.write(input, ((String) c.get("input")).getBytes(StandardCharsets.UTF_8));
            Outcome o = runFile(kind, input);
            boolean ok = o.code == ((Double) c.get("code")).intValue() && o.out.equals(normal((String) c.get("out")))
                    && o.err.equals(normal((String) c.get("err")));
            if (ok) {
                same++;
            } else if (different.size() < 5) {
                different.add(c.get("name") + ": got " + o.code + " " + abbreviate(o.err) + " / expected " + c.get("code") + " "
                        + abbreviate((String) c.get("err")));
            }
        }
        check("%s: %d cases recorded from the original give the same exit code, output and errors".formatted(kind, cases.size()),
                same == cases.size(), same + " of " + cases.size() + "; " + String.join(" | ", different));
    }

    private static String abbreviate(String text) {
        String one = text.replace('\n', ' ');
        return one.length() > 160 ? one.substring(0, 160) + "..." : one;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Inputs a text file cannot hold
    // ---------------------------------------------------------------------------------------------------------------

    private static void specialInputs() throws Exception {
        for (String kind : List.of("findings", "ledger")) {
            String reading = kind.equals("findings") ? "Failed to read findings JSON: " : "Failed to read coverage ledger: ";
            String pass = kind.equals("findings") ? "PASS: 0 findings valid\n" : "PASS: 0 coverage units valid\n";
            Path file = temp.resolve("special-" + kind + ".json");

            Files.write(file, new byte[] {0x5b, 0x22, (byte) 0xc3, 0x28, 0x22, 0x5d});
            Outcome bad = runFile(kind, file);
            check(kind + ": invalid UTF-8 is refused", bad.code == 1 && bad.err.equals(reading + "input is not valid UTF-8\n"), bad.err);

            Files.write(file, new byte[] {0x5b, 0x22, (byte) 0xc0, (byte) 0xaf, 0x22, 0x5d});
            Outcome overlong = runFile(kind, file);
            check(kind + ": an overlong UTF-8 sequence is refused", overlong.err.equals(reading + "input is not valid UTF-8\n"), overlong.err);

            byte[] limit = new byte[5 * 1024 * 1024];
            java.util.Arrays.fill(limit, (byte) ' ');
            limit[0] = '[';
            limit[limit.length - 1] = ']';
            Files.write(file, limit);
            Outcome exact = runFile(kind, file);
            check(kind + ": a file of exactly the size limit is read", exact.code == 0 && exact.out.equals(pass), exact.err);

            byte[] over = new byte[limit.length + 1];
            java.util.Arrays.fill(over, (byte) ' ');
            Files.write(file, over);
            Outcome tooBig = runFile(kind, file);
            check(kind + ": a file one byte over the limit is refused", tooBig.code == 1 && tooBig.err.equals(reading + "input exceeds 5242880 byte limit\n"),
                    tooBig.err);

            Outcome directory = runFile(kind, Files.createDirectories(temp.resolve("a-directory-" + kind)));
            check(kind + ": a directory is not a regular file", directory.err.equals(reading + "input must be a regular file\n"), directory.err);

            Outcome missing = runFile(kind, temp.resolve("missing-" + kind + ".json"));
            check(kind + ": a missing file is said to be unreadable", missing.err.equals(reading + "input could not be opened or read safely\n"),
                    missing.err);

            Files.write(file, "[]".getBytes(StandardCharsets.UTF_8));
            Path link = temp.resolve("link-" + kind + ".json");
            try {
                Files.createSymbolicLink(link, file);
                Outcome linked = runFile(kind, link);
                check(kind + ": a symbolic link is refused", linked.code == 1 && linked.err.equals(reading + "input must not be a symlink\n"), linked.err);
            } catch (IOException | UnsupportedOperationException e) {
                System.out.println("SKIP  " + kind + ": a symbolic link is refused (this account cannot create symbolic links: " + e.getMessage() + ")");
            }

            Files.write(file, "[]".getBytes(StandardCharsets.UTF_8));
            Outcome usage = kind.equals("findings") ? run(kind) : run(kind);
            check(kind + ": no argument prints the usage and exits 1", usage.code == 1 && usage.err.startsWith("Usage: java Validate"), usage.err);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The JavaScript behaviours the rules rely on
    // ---------------------------------------------------------------------------------------------------------------

    private static void helpers() throws Exception {
        Method number = method("Json", "numberToString", double.class);
        record Num(double value, String expected) {
        }
        for (Num n : List.of(new Num(0, "0"), new Num(-0.0, "0"), new Num(1, "1"), new Num(-1.5, "-1.5"), new Num(100, "100"),
                new Num(1e21, "1e+21"), new Num(1e20, "100000000000000000000"), new Num(1.5e-7, "1.5e-7"), new Num(0.000001, "0.000001"),
                new Num(0.1, "0.1"), new Num(5e-324, "5e-324"), new Num(123456789012345680000.0, "123456789012345680000"),
                new Num(1.7976931348623157e308, "1.7976931348623157e+308"), new Num(2.5, "2.5"))) {
            String got = (String) number.invoke(null, n.value);
            check("a number prints as JavaScript prints it: " + n.expected, got.equals(n.expected), got);
        }
        Method stringify = method("Json", "stringify", String.class);
        check("stringify escapes quotes, backslashes and controls", stringify.invoke(null, "a\"b\\c\n\u0001").equals("\"a\\\"b\\\\c\\n\\u0001\""));
        check("stringify writes a lone surrogate as an escape", stringify.invoke(null, "x\ud800y").equals("\"x\\ud800y\""));
        check("stringify keeps a surrogate pair", stringify.invoke(null, "😀").equals("\"😀\""));
        Method trim = method("Json", "jsTrim", String.class);
        check("trim removes a no-break space and a byte order mark, as JavaScript does", trim.invoke(null, " ﻿ x  ").equals("x"));
        check("trim keeps a zero-width space, as JavaScript does", trim.invoke(null, "​x").equals("​x"));
        Method escape = method("Json", "escapeUnsafe", String.class);
        check("a right-to-left override in a message is shown as an escape", escape.invoke(null, "a‮b").equals("a\\u202eb"));
        check("a tag character beyond the basic plane is shown as an escape", escape.invoke(null, new String(Character.toChars(0xe0041))).equals("\\u{e0041}"));
        check("a lone surrogate in a message is shown as an escape", escape.invoke(null, "a\ud800").equals("a\\ud800"));
        check("an ordinary accented letter is left alone", escape.invoke(null, "café").equals("café"));
        Method keys = method("Json", "keys", Map.class);
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("b", 1.0);
        map.put("2", 1.0);
        map.put("a", 1.0);
        map.put("1", 1.0);
        map.put("01", 1.0);
        check("object keys list integer-like keys first, ascending, as JavaScript does", keys.invoke(null, map).toString().equals("[1, 2, b, a, 01]"));
        Method parse = method("Json", "parse", String.class);
        Object doc = parse.invoke(null, "{\"a\":1,\"a\":2,\"b\":0.5}");
        check("a duplicate key keeps the last value", doc.toString().equals("{a=2.0, b=0.5}"), doc.toString());
        try {
            parse.invoke(null, "[1,]");
            check("a trailing comma is a syntax error", false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            check("a trailing comma is a syntax error", e.getCause().getClass().getSimpleName().equals("SyntaxError"));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The command line, as the agents run it
    // ---------------------------------------------------------------------------------------------------------------

    private record Process2(int code, String out, String err) {
    }

    private static Process2 java(Path source, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), source.toString()));
        command.addAll(List.of(args));
        Path out = temp.resolve("cli-out.txt");
        Path err = temp.resolve("cli-err.txt");
        Process p = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Process2(-1, "", "timed out");
        }
        return new Process2(p.exitValue(), normal(Files.readString(out)), normal(Files.readString(err)));
    }

    private static void commandLine() throws Exception {
        Path run = kit.resolve("tests/security-audit/fixtures/run-ok");
        Process2 ok = java(skill.resolve("ValidateFindings.java"), run.resolve("findings.json").toString());
        check("java ValidateFindings.java <file>: a valid file passes (the schema is found next to the source)", ok.code == 0
                && ok.out.startsWith("PASS: 4 findings valid"), ok.err + ok.out);
        Process2 ledger = java(skill.resolve("ValidateCoverageLedger.java"), run.resolve("coverage-ledger.json").toString());
        check("java ValidateCoverageLedger.java <file>: a valid file passes", ledger.code == 0 && ledger.out.startsWith("PASS: 4 coverage units valid"),
                ledger.err);

        Path broken = temp.resolve("broken-findings.json");
        Files.writeString(broken, "[{\"verdict\":\"confirmed\"}]");
        Process2 bad = java(skill.resolve("ValidateFindings.java"), broken.toString());
        check("an invalid file exits 1 with the rule's message and a count", bad.code == 1 && bad.err.contains("ERROR: $[0]:") && bad.err.contains("FAIL: "),
                bad.err);

        Path spaced = Files.createDirectories(temp.resolve("a folder with spaces/skill"));
        for (String name : List.of("Json.java", "ValidateFindings.java", "report-schema.json")) {
            Files.copy(skill.resolve(name), spaced.resolve(name));
        }
        Process2 moved = java(spaced.resolve("ValidateFindings.java"), run.resolve("findings.json").toString());
        check("the schema is found when the folder has spaces in its name", moved.code == 0 && moved.out.startsWith("PASS"), moved.err);

        Files.delete(spaced.resolve("report-schema.json"));
        Process2 noSchema = java(spaced.resolve("ValidateFindings.java"), run.resolve("findings.json").toString());
        check("a missing schema is reported, not ignored", noSchema.code == 1 && noSchema.err.startsWith("Failed to load report-schema.json:"), noSchema.err);

        Process2 old = java(skill.resolve("ValidateFindings.java"));
        check("no argument: usage and exit 1", old.code == 1 && old.err.startsWith("Usage: java ValidateFindings.java"), old.err);
    }

    private static void validateRun() throws Exception {
        Path ok = kit.resolve("tests/security-audit/fixtures/run-ok");
        Process2 good = java(tools.resolve("ValidateRun.java"), ok.toString());
        check("ValidateRun: a valid run exits 0, says PASS twice and valid:true", good.code == 0 && good.out.contains("[findings] PASS:")
                && good.out.contains("[coverage-ledger] PASS:") && good.out.contains("\"valid\":true"), good.err + good.out);
        check("ValidateRun: there is no weaker mode on any operating system", !good.out.contains("weakened") && !good.out.contains("wrapper") && !good.out.contains("mode"));

        Path run = Files.createDirectories(temp.resolve("run-bad"));
        Files.copy(ok.resolve("findings.json"), run.resolve("findings.json"));
        Files.writeString(run.resolve("coverage-ledger.json"), "[{}]");
        Process2 bad = java(tools.resolve("ValidateRun.java"), run.toString());
        check("ValidateRun: a real validation error fails the run with the rule's message", bad.code == 1 && bad.err.contains("[coverage-ledger] FAIL")
                && bad.err.contains("missing required field") && bad.out.contains("\"valid\":false"), bad.err + bad.out);

        Files.delete(run.resolve("coverage-ledger.json"));
        Process2 missing = java(tools.resolve("ValidateRun.java"), run.toString());
        check("ValidateRun: a missing file fails, it is never skipped", missing.code == 1 && missing.err.contains("coverage-ledger.json not found"),
                missing.err);

        Process2 usage = java(tools.resolve("ValidateRun.java"));
        check("ValidateRun: no run directory is a usage error (exit 2)", usage.code == 2 && usage.err.contains("usage: java ValidateRun.java"), usage.err);
        Process2 unknown = java(tools.resolve("ValidateRun.java"), ok.toString(), "--nope");
        check("ValidateRun: an unknown option is a usage error (exit 2)", unknown.code == 2 && unknown.err.contains("unknown option --nope"), unknown.err);
        Process2 gone = java(tools.resolve("ValidateRun.java"), temp.resolve("no-such-run").toString());
        check("ValidateRun: a missing run directory is a usage error (exit 2)", gone.code == 2 && gone.err.contains("run directory not found"), gone.err);
        Process2 elsewhere = java(tools.resolve("ValidateRun.java"), ok.toString(), "--skill-dir", skill.toString());
        check("ValidateRun: --skill-dir names the folder of the validators", elsewhere.code == 0, elsewhere.err);
    }

    private static void deleteTree(Path folder) {
        try (var walk = Files.walk(folder)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException _) {
            // the system cleans its temporary folder
        }
    }
}
