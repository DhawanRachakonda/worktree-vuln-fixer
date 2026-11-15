import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Security audit run -> issues CSV. Pure Java 21, no dependencies, runnable as a single file:
 *
 * <pre>
 *   java FindingsToCsv.java &lt;run-dir&gt; --repo DIR --out DIR [--max-cell 4000] [--generated-at 2026-01-31T10:00:00Z]
 * </pre>
 *
 * <p>{@code run-dir} is the run directory of the Cloudflare security-audit skill. The tool reads
 * {@code findings.json} (required) plus {@code run-metadata.json} (the reviewed commit) and
 * {@code coverage-ledger.json} (the attack class of each finding) when present, and writes
 * {@code DIR/issues.csv}: one row per evidence location of every {@code confirmed} or {@code needs_validation}
 * finding. {@code rejected} findings are checked but not listed.
 *
 * <p>Findings are bound into the typed records below, following the skill's {@code report-schema.json}. The fields the
 * CSV uses are checked strictly; an unknown field, an unknown verdict, a missing path or line, a duplicate fingerprint
 * or a bad path stops the run (exit 3) naming the finding, and no CSV is written. Text from the audited code is
 * untrusted: cells that a spreadsheet could evaluate as a formula are prefixed with a single quote.
 *
 * <p>Output: the CSV, and as the last line on stdout one JSON object with the counts. Exit codes: 0 ok, 2 usage,
 * 3 invalid input, 4 I/O error.
 */
public final class FindingsToCsv {

    // ---------------------------------------------------------------------------------------------------------
    // Typed model (the subset of report-schema.json the CSV uses)
    // ---------------------------------------------------------------------------------------------------------

    record TraceStep(String kind, String file, int line) {}

    record Evidence(String file, int line, String description) {}

    sealed interface Finding permits Confirmed, NeedsValidation, Rejected {
        String fingerprint();

        String title();

        String description();

        List<TraceStep> trace();

        List<Evidence> evidence();
    }

    record Confirmed(String fingerprint, String title, String description, String rootCause, List<TraceStep> trace,
                     List<Evidence> evidence, String remediation, String severity, String confidence)
            implements Finding {}

    record NeedsValidation(String fingerprint, String title, String description, String claimedRootCause,
                           List<TraceStep> trace, List<Evidence> evidence, List<String> blockers,
                           String validationLocal, String validationDeployment) implements Finding {}

    record Rejected(String fingerprint, String title, String description, String claimedRootCause,
                    List<TraceStep> trace, List<Evidence> evidence, String reason) implements Finding {}

    /** Invalid findings, metadata or ledger: the CSV is not written. */
    static final class InvalidInput extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidInput(String message) {
            super(message);
        }
    }

    static final class UsageError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageError(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // CSV layout
    // ---------------------------------------------------------------------------------------------------------

    static final List<String> COLUMNS = List.of("file", "line", "title", "fingerprint", "verdict", "severity",
            "confidence", "location_note", "description", "root_cause", "trace", "remediation", "blockers",
            "validation_plan", "attack_class", "commit", "generated_at");

    private static final int LINE_COLUMN = 1;
    /** Columns never shortened: identifiers and short enumerations. */
    private static final Set<Integer> UNCAPPED = Set.of(0, 1, 3, 4, 5, 6, 14, 15, 16);

    private static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "informational");
    private static final List<String> LEVELS = List.of("informational", "low", "medium", "high", "critical");
    private static final List<String> CONFIDENCES = List.of("low", "medium", "high");
    private static final List<String> TRACE_KINDS = List.of("entrypoint", "propagation", "sink");
    private static final Pattern FINGERPRINT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$");
    private static final long MAX_INPUT_BYTES = 16L * 1024 * 1024;
    private static final String TRUNCATED = " [truncated]";
    private static final int DEFAULT_MAX_CELL = 4000;

    private FindingsToCsv() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            return execute(args, out, err);
        } catch (UsageError e) {
            err.println("FindingsToCsv: " + e.getMessage());
            err.println("usage: java FindingsToCsv.java <run-dir> --repo DIR --out DIR [--max-cell N] [--generated-at ISO-8601]");
            return 2;
        } catch (InvalidInput e) {
            err.println("FindingsToCsv: " + e.getMessage());
            return 3;
        } catch (IOException e) {
            err.println("FindingsToCsv: I/O error: " + e.getMessage());
            return 4;
        }
    }

    private static int execute(String[] args, PrintStream out, PrintStream err) throws IOException {
        Path runDir = null;
        Path outDir = null;
        Path repo = null;
        int maxCell = DEFAULT_MAX_CELL;
        String generatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> outDir = Path.of(value(args, ++i, "--out"));
                case "--repo" -> repo = Path.of(value(args, ++i, "--repo"));
                case "--max-cell" -> maxCell = positive(value(args, ++i, "--max-cell"));
                case "--generated-at" -> generatedAt = Instant.parse(value(args, ++i, "--generated-at")).toString();
                default -> {
                    if (args[i].startsWith("--")) throw new UsageError("unknown option " + args[i]);
                    if (runDir != null) throw new UsageError("only one run directory may be given");
                    runDir = Path.of(args[i]);
                }
            }
        }
        if (runDir == null) throw new UsageError("the skill run directory is required");
        if (outDir == null) throw new UsageError("--out DIR is required");
        if (repo == null) throw new UsageError("--repo DIR (the audited repository) is required");
        if (maxCell < 100) throw new UsageError("--max-cell must be at least 100");
        if (!Files.isDirectory(runDir)) throw new InvalidInput("run directory not found: " + runDir);

        Path findingsFile = runDir.resolve("findings.json");
        if (!Files.isRegularFile(findingsFile)) throw new InvalidInput("findings.json not found in " + runDir);
        List<Finding> findings = bind(readJson(findingsFile));
        verifyLocations(repo, findings);

        String commit = "";
        Path metadataFile = runDir.resolve("run-metadata.json");
        if (Files.isRegularFile(metadataFile)) {
            commit = renderSourceRef(readJson(metadataFile));
        } else {
            err.println("FindingsToCsv: warning: run-metadata.json not found, the commit column is empty");
        }
        Map<String, String> attackClasses = Map.of();
        Path ledgerFile = runDir.resolve("coverage-ledger.json");
        if (Files.isRegularFile(ledgerFile)) {
            attackClasses = attackClassesByFingerprint(readJson(ledgerFile));
        } else {
            err.println("FindingsToCsv: warning: coverage-ledger.json not found, the attack_class column is empty");
        }

        List<Row> rows = rows(findings, attackClasses, commit, generatedAt);
        Path csv = write(outDir, rows, maxCell);
        out.println(summary(findings, rows.size(), commit, csv));
        return 0;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) throw new UsageError(option + " needs a value");
        return args[i];
    }

    private static int positive(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new UsageError("not a number: " + text);
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Binding findings.json into the typed model
    // ---------------------------------------------------------------------------------------------------------

    static List<Finding> bind(Object root) {
        if (!(root instanceof List<?> list)) throw new InvalidInput("findings.json must be a JSON array of findings");
        List<Finding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Obj o = Obj.of(list.get(i), "findings.json record #" + (i + 1));
            String verdict = o.peekString("verdict");
            if (verdict == null) throw new InvalidInput(o.where + ": missing 'verdict'");
            String fingerprint = o.peekString("fingerprint");
            if (fingerprint != null) o.where = "finding '" + fingerprint + "'";
            Finding f = switch (verdict) {
                case "confirmed" -> confirmed(o);
                case "needs_validation" -> needsValidation(o);
                case "rejected" -> rejected(o);
                default -> throw new InvalidInput(o.where + ": unknown verdict '" + verdict + "'");
            };
            if (!seen.add(f.fingerprint())) {
                throw new InvalidInput("finding '" + f.fingerprint() + "': appears more than once");
            }
            out.add(f);
        }
        return out;
    }

    private static Confirmed confirmed(Obj o) {
        o.str("verdict");
        String fingerprint = fingerprint(o);
        String title = o.str("title");
        String description = o.str("description");
        String rootCause = o.str("root_cause");
        o.str("intended_behavior");
        List<TraceStep> trace = trace(o);
        List<Evidence> evidence = evidence(o);
        for (Obj c : o.objects("conditions", false)) {
            c.str("kind");
            c.str("description");
            c.done();
        }
        Obj execution = o.obj("execution");
        execution.str("attacker_perspective");
        execution.strings("payloads", true);
        execution.strings("instructions", true);
        execution.str("observed_result");
        execution.done();
        Obj remediation = o.obj("remediation");
        String strategy = remediation.str("strategy");
        if (remediation.has("code_changes")) {
            for (Obj change : remediation.objects("code_changes", false)) {
                change.str("file_name");
                change.raw("fixed_code", String.class);
                change.done();
            }
        }
        remediation.done();
        Obj severity = o.obj("severity");
        rating(severity.obj("likelihood"), LEVELS);
        rating(severity.obj("impact"), LEVELS);
        String overall = severity.oneOf("overall_severity", SEVERITIES);
        severity.done();
        String confidence = rating(o.obj("confidence"), CONFIDENCES);
        o.done();
        return new Confirmed(fingerprint, title, description, rootCause, trace, evidence, strategy, overall, confidence);
    }

    private static NeedsValidation needsValidation(Obj o) {
        o.str("verdict");
        String fingerprint = fingerprint(o);
        String title = o.str("title");
        String description = o.str("description");
        String claimed = o.str("claimed_root_cause");
        List<TraceStep> trace = trace(o);
        List<Evidence> evidence = evidence(o);
        List<String> blockers = o.strings("blockers", true);
        Obj plan = o.obj("validation_plan");
        String local = plan.has("local") ? plan.str("local") : "";
        String deployment = plan.has("deployment") ? plan.str("deployment") : "";
        plan.done();
        o.done();
        return new NeedsValidation(fingerprint, title, description, claimed, trace, evidence, blockers, local,
                deployment);
    }

    private static Rejected rejected(Obj o) {
        o.str("verdict");
        String fingerprint = fingerprint(o);
        String title = o.str("title");
        String description = o.str("description");
        String claimed = o.str("claimed_root_cause");
        List<TraceStep> trace = trace(o);
        List<Evidence> evidence = evidence(o);
        String reason = o.str("reason");
        o.done();
        return new Rejected(fingerprint, title, description, claimed, trace, evidence, reason);
    }

    private static String fingerprint(Obj o) {
        String fingerprint = o.str("fingerprint");
        if (!FINGERPRINT.matcher(fingerprint).matches()) {
            throw new InvalidInput(o.where + ": 'fingerprint' has characters the schema does not allow");
        }
        return fingerprint;
    }

    private static List<TraceStep> trace(Obj o) {
        List<TraceStep> steps = new ArrayList<>();
        for (Obj step : o.objects("trace", true)) {
            String kind = step.oneOf("kind", TRACE_KINDS);
            String file = path(step, "file");
            int line = line(step);
            step.str("scope");
            step.str("description");
            step.done();
            steps.add(new TraceStep(kind, file, line));
        }
        return steps;
    }

    private static List<Evidence> evidence(Obj o) {
        List<Evidence> items = new ArrayList<>();
        for (Obj e : o.objects("evidence", true)) {
            String file = path(e, "file");
            int line = line(e);
            String description = e.str("description");
            e.done();
            items.add(new Evidence(file, line, description));
        }
        return items;
    }

    private static String rating(Obj o, List<String> allowed) {
        String score = o.oneOf("score", allowed);
        o.str("reason");
        o.done();
        return score;
    }

    private static int line(Obj o) {
        Object v = o.raw("line", Object.class);
        if (v instanceof Long n && n >= 1 && n <= Integer.MAX_VALUE) return n.intValue();
        throw new InvalidInput(o.where + ": 'line' must be a whole number of 1 or more");
    }

    /** A repository-relative path with forward slashes; absolute paths and parent references are refused. */
    private static String path(Obj o, String key) {
        String raw = o.str(key);
        String p = raw.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        boolean absolute = p.startsWith("/") || p.startsWith("~") || p.matches("^[A-Za-z]:.*");
        boolean parent = false;
        for (String segment : p.split("/", -1)) parent |= segment.equals("..");
        boolean control = p.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
        if (p.isEmpty() || absolute || parent || control) {
            throw new InvalidInput(o.where + ": '" + key + "' is not a repository-relative path");
        }
        return p;
    }

    /** One JSON object read through typed accessors; {@link #done()} rejects fields nothing asked for. */
    private static final class Obj {
        final Map<String, Object> map;
        final Set<String> used = new HashSet<>();
        String where;

        private Obj(Map<String, Object> map, String where) {
            this.map = map;
            this.where = where;
        }

        @SuppressWarnings("unchecked")
        static Obj of(Object value, String where) {
            if (!(value instanceof Map<?, ?> m)) throw new InvalidInput(where + ": expected an object");
            return new Obj((Map<String, Object>) m, where);
        }

        boolean has(String key) {
            return map.containsKey(key);
        }

        String peekString(String key) {
            return map.get(key) instanceof String s && !s.isEmpty() ? s : null;
        }

        <T> T raw(String key, Class<T> type) {
            used.add(key);
            if (!map.containsKey(key)) throw new InvalidInput(where + ": missing '" + key + "'");
            Object v = map.get(key);
            if (!type.isInstance(v)) throw new InvalidInput(where + ": '" + key + "' has the wrong type");
            return type.cast(v);
        }

        String str(String key) {
            String s = raw(key, String.class);
            if (s.isEmpty()) throw new InvalidInput(where + ": '" + key + "' must not be empty");
            return s;
        }

        String oneOf(String key, List<String> allowed) {
            String s = raw(key, String.class);
            if (!allowed.contains(s)) {
                throw new InvalidInput(where + ": '" + key + "' must be one of " + allowed + ", not '" + s + "'");
            }
            return s;
        }

        Obj obj(String key) {
            return of(raw(key, Object.class), where + "." + key);
        }

        List<Obj> objects(String key, boolean nonEmpty) {
            List<?> items = raw(key, List.class);
            if (nonEmpty && items.isEmpty()) throw new InvalidInput(where + ": '" + key + "' must not be empty");
            List<Obj> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) out.add(of(items.get(i), where + "." + key + "[" + i + "]"));
            return out;
        }

        List<String> strings(String key, boolean nonEmpty) {
            List<?> items = raw(key, List.class);
            if (nonEmpty && items.isEmpty()) throw new InvalidInput(where + ": '" + key + "' must not be empty");
            List<String> out = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof String s) || (s.isEmpty() && !key.equals("payloads"))) {
                    throw new InvalidInput(where + ": '" + key + "' must hold text");
                }
                out.add((String) item);
            }
            return out;
        }

        void done() {
            for (String key : map.keySet()) {
                if (!used.contains(key)) throw new InvalidInput(where + ": unexpected field '" + key + "'");
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // Locations must exist in the audited repository
    // ---------------------------------------------------------------------------------------------------------

    /**
     * Every file and line a listed finding cites (evidence and trace) must exist in the repository: a regular file
     * inside it (symlinks are resolved first), and a line number within the file. A model that numbers lines across
     * several files at once produces plausible but impossible lines; the skill's validators do not catch that.
     * This proves the location exists, not that it is the line of the flaw.
     */
    static void verifyLocations(Path repo, List<Finding> findings) throws IOException {
        if (!Files.isDirectory(repo)) throw new InvalidInput("repository not found: " + repo);
        Path root = repo.toRealPath();
        Map<String, Integer> lineCounts = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Finding f : findings) {
            if (f instanceof Rejected) continue;
            Set<String> seen = new HashSet<>();
            List<String[]> locations = new ArrayList<>();
            for (Evidence e : f.evidence()) locations.add(new String[]{e.file(), Integer.toString(e.line())});
            for (TraceStep s : f.trace()) locations.add(new String[]{s.file(), Integer.toString(s.line())});
            for (String[] loc : locations) {
                if (!seen.add(loc[0] + ":" + loc[1])) continue;
                int line = Integer.parseInt(loc[1]);
                Integer count = lineCounts.get(loc[0]);
                if (count == null) {
                    count = countLines(root, loc[0]);
                    lineCounts.put(loc[0], count);
                }
                String where = "finding '" + f.fingerprint() + "': " + loc[0] + ":" + line;
                if (count < 0) {
                    problems.add(where + " - the file is not in the repository");
                } else if (line > count) {
                    problems.add(where + " - past the end of the file (" + count + " lines)");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidInput(problems.size() + " cited location(s) do not exist in the repository; re-read the"
                    + " files and correct the line numbers:\n  " + String.join("\n  ", problems));
        }
    }

    /** Line count of a regular file inside the repository, or -1 when it is missing, not a file, or outside it. */
    private static int countLines(Path root, String relative) throws IOException {
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return -1;
        if (!file.toRealPath().startsWith(root)) return -1;
        byte[] bytes = Files.readAllBytes(file);
        int lines = 0;
        for (byte b : bytes) if (b == '\n') lines++;
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') lines++;
        return lines;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Run metadata and coverage ledger
    // ---------------------------------------------------------------------------------------------------------

    /** The reviewed commit as recorded in {@code run-metadata.json}; its shape is not fixed, so it is rendered as given. */
    static String renderSourceRef(Object metadata) {
        if (!(metadata instanceof Map<?, ?> m)) throw new InvalidInput("run-metadata.json must be a JSON object");
        return render(m.get("source_ref"));
    }

    private static String render(Object v) {
        if (v == null) return "";
        if (v instanceof Map<?, ?> m) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) parts.add(e.getKey() + "=" + render(e.getValue()));
            return String.join(", ", parts);
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object item : l) parts.add(render(item));
            return String.join(", ", parts);
        }
        return String.valueOf(v);
    }

    /** Attack class of each finding: the coverage units whose result fingerprints include it. */
    static Map<String, String> attackClassesByFingerprint(Object ledger) {
        if (!(ledger instanceof List<?> units)) throw new InvalidInput("coverage-ledger.json must be a JSON array");
        Map<String, Set<String>> byFingerprint = new TreeMap<>();
        for (int i = 0; i < units.size(); i++) {
            if (!(units.get(i) instanceof Map<?, ?> unit)) {
                throw new InvalidInput("coverage-ledger.json unit #" + (i + 1) + ": expected an object");
            }
            if (!(unit.get("result_fingerprints") instanceof List<?> fingerprints) || fingerprints.isEmpty()) continue;
            String label = attackClassLabel(unit);
            if (label.isEmpty()) continue;
            for (Object fp : fingerprints) {
                if (fp instanceof String s) byFingerprint.computeIfAbsent(s, k -> new TreeSet<>()).add(label);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        byFingerprint.forEach((fp, labels) -> out.put(fp, String.join("; ", labels)));
        return out;
    }

    private static String attackClassLabel(Map<?, ?> unit) {
        if (unit.get("canonical_refs") instanceof Map<?, ?> refs && refs.get("attack_class") instanceof String ref) {
            int hash = ref.indexOf('#');
            if (hash >= 0 && hash < ref.length() - 1) return ref.substring(hash + 1);
            if (!ref.isEmpty()) return ref;
        }
        return unit.get("attack_class") instanceof String s ? s : "";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Rows and CSV
    // ---------------------------------------------------------------------------------------------------------

    record Row(Finding finding, String[] cells) {}

    static List<Row> rows(List<Finding> findings, Map<String, String> attackClasses, String commit,
                          String generatedAt) {
        List<Row> rows = new ArrayList<>();
        for (Finding f : findings) {
            if (f instanceof Rejected) continue;
            String trace = String.join(" -> ", f.trace().stream()
                    .map(s -> s.kind() + " " + s.file() + ":" + s.line()).toList());
            for (Evidence e : f.evidence()) {
                String verdict;
                String severity = "";
                String confidence = "";
                String rootCause;
                String remediation = "";
                String blockers = "";
                String plan = "";
                if (f instanceof Confirmed c) {
                    verdict = "confirmed";
                    severity = c.severity();
                    confidence = c.confidence();
                    rootCause = c.rootCause();
                    remediation = c.remediation();
                } else {
                    NeedsValidation n = (NeedsValidation) f;
                    verdict = "needs_validation";
                    rootCause = n.claimedRootCause();
                    blockers = String.join(" | ", n.blockers());
                    plan = validationPlan(n);
                }
                rows.add(new Row(f, new String[]{e.file(), Integer.toString(e.line()), f.title(), f.fingerprint(),
                        verdict, severity, confidence, e.description(), f.description(), rootCause, trace, remediation,
                        blockers, plan, attackClasses.getOrDefault(f.fingerprint(), ""), commit, generatedAt}));
            }
        }
        rows.sort(Comparator
                .comparingInt((Row r) -> r.finding() instanceof Confirmed ? 0 : 1)
                .thenComparingInt(r -> severityRank(r.cells()[5]))
                .thenComparing(r -> r.cells()[0])
                .thenComparingInt(r -> Integer.parseInt(r.cells()[1]))
                .thenComparing(r -> r.cells()[3]));
        return rows;
    }

    private static String validationPlan(NeedsValidation n) {
        List<String> parts = new ArrayList<>();
        if (!n.validationLocal().isEmpty()) parts.add("local: " + n.validationLocal());
        if (!n.validationDeployment().isEmpty()) parts.add("deployment: " + n.validationDeployment());
        return String.join(" | ", parts);
    }

    private static int severityRank(String severity) {
        int i = SEVERITIES.indexOf(severity);
        return i < 0 ? SEVERITIES.size() : i;
    }

    /** Writes {@code outDir/issues.csv} (UTF-8 with BOM, CRLF) through a temporary file, so no partial CSV is left. */
    private static Path write(Path outDir, List<Row> rows, int maxCell) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        appendRecord(sb, COLUMNS.toArray(new String[0]), Integer.MAX_VALUE, false);
        for (Row row : rows) appendRecord(sb, row.cells(), maxCell, true);
        Files.createDirectories(outDir);
        Path target = outDir.resolve("issues.csv");
        Path temp = Files.createTempFile(outDir, "issues-", ".tmp");
        try {
            Files.write(temp, sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private static void appendRecord(StringBuilder sb, String[] cells, int maxCell, boolean guard) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            String cell = cells[i];
            if (guard && i != LINE_COLUMN) cell = neutralizeFormula(cell);
            if (guard && !UNCAPPED.contains(i)) cell = cap(cell, maxCell);
            sb.append(quote(cell));
        }
        sb.append("\r\n");
    }

    /** A cell a spreadsheet could run as a formula gets a leading single quote, so it is shown as text. */
    static String neutralizeFormula(String cell) {
        int i = 0;
        while (i < cell.length() && (cell.charAt(i) == ' ')) i++;
        if (i < cell.length()) {
            char c = cell.charAt(i);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') return "'" + cell;
        }
        return cell;
    }

    static String cap(String cell, int max) {
        if (cell.codePointCount(0, cell.length()) <= max) return cell;
        int keep = max - TRUNCATED.length();
        return cell.substring(0, cell.offsetByCodePoints(0, keep)) + TRUNCATED;
    }

    /** RFC 4180: quote when the cell holds a comma, quote or line break (or edge spaces), doubling inner quotes. */
    static String quote(String cell) {
        boolean needs = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0
                || cell.indexOf('\r') >= 0 || cell.startsWith(" ") || cell.endsWith(" ");
        return needs ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Summary (last stdout line)
    // ---------------------------------------------------------------------------------------------------------

    private static String summary(List<Finding> findings, int rows, String commit, Path csv) {
        int confirmed = 0;
        int needsValidation = 0;
        int rejected = 0;
        Map<String, Integer> severities = new LinkedHashMap<>();
        for (String s : SEVERITIES) severities.put(s, 0);
        for (Finding f : findings) {
            switch (f) {
                case Confirmed c -> {
                    confirmed++;
                    severities.merge(c.severity(), 1, Integer::sum);
                }
                case NeedsValidation n -> needsValidation++;
                case Rejected r -> rejected++;
            }
        }
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"csv\":").append(jsonString(csv.toAbsolutePath().toString()));
        sb.append(",\"issues\":").append(confirmed + needsValidation);
        sb.append(",\"rows\":").append(rows);
        sb.append(",\"locationsChecked\":true");
        sb.append(",\"verdicts\":{\"confirmed\":").append(confirmed)
                .append(",\"needs_validation\":").append(needsValidation)
                .append(",\"rejected\":").append(rejected).append('}');
        sb.append(",\"severities\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : severities.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(jsonString(e.getKey())).append(':').append(e.getValue());
        }
        sb.append("},\"commit\":").append(jsonString(commit)).append('}');
        return sb.toString();
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Minimal JSON reader (fills the typed model; findings.json holds text from the audited code, so it is bounded)
    // ---------------------------------------------------------------------------------------------------------

    private static Object readJson(Path file) throws IOException {
        if (Files.size(file) > MAX_INPUT_BYTES) {
            throw new InvalidInput(file.getFileName() + " is larger than " + MAX_INPUT_BYTES / (1024 * 1024) + " MiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidInput(file.getFileName() + " is not valid UTF-8");
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        try {
            return new JsonReader(text).parse();
        } catch (InvalidInput e) {
            throw new InvalidInput(file.getFileName() + " is not valid JSON: " + e.getMessage());
        }
    }

    private static final class JsonReader {
        private static final int MAX_DEPTH = 64;
        private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
        private final String s;
        private int i;

        JsonReader(String s) {
            this.s = s;
        }

        Object parse() {
            skipWhitespace();
            Object v = value(0);
            skipWhitespace();
            if (i != s.length()) throw fail("unexpected content after the value");
            return v;
        }

        private InvalidInput fail(String what) {
            return new InvalidInput(what + " at offset " + i);
        }

        private void skipWhitespace() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n'
                    || s.charAt(i) == '\r')) i++;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw fail("nesting deeper than " + MAX_DEPTH);
            if (i >= s.length()) throw fail("unexpected end");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Object literal(String word, Object result) {
            if (!s.startsWith(word, i)) throw fail("invalid value");
            i += word.length();
            return result;
        }

        private Object number() {
            var m = NUMBER.matcher(s).region(i, s.length());
            if (!m.lookingAt()) throw fail("invalid value");
            String text = m.group();
            i = m.end();
            if (m.group(2) == null && m.group(3) == null) {
                try {
                    return Long.parseLong(text);
                } catch (NumberFormatException e) {
                    return Double.parseDouble(text);
                }
            }
            return Double.parseDouble(text);
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<>();
            i++;
            skipWhitespace();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (!peek('"')) throw fail("expected a key");
                String key = string();
                skipWhitespace();
                if (!peek(':')) throw fail("expected ':'");
                i++;
                skipWhitespace();
                Object v = value(depth + 1);
                if (map.containsKey(key)) throw fail("duplicate key '" + key + "'");
                map.put(key, v);
                skipWhitespace();
                if (peek(',')) {
                    i++;
                } else if (peek('}')) {
                    i++;
                    return map;
                } else {
                    throw fail("expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<>();
            i++;
            skipWhitespace();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth + 1));
                skipWhitespace();
                if (peek(',')) {
                    i++;
                } else if (peek(']')) {
                    i++;
                    return list;
                } else {
                    throw fail("expected ',' or ']'");
                }
            }
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw fail("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw fail("control character in string");
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) throw fail("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw fail("bad \\u escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw fail("bad \\u escape");
                        }
                        i += 4;
                    }
                    default -> throw fail("bad escape");
                }
            }
        }
    }
}
