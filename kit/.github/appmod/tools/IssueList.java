import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads a security audit CSV (the file the {@code security-audit} agent writes with {@code FindingsToCsv.java}) and prints its issues
 * in the order a fixer should work on them, so that an agent never parses quoted CSV text by hand.
 *
 * <pre>
 *   java IssueList.java &lt;issues.csv&gt; [--json]
 * </pre>
 *
 * Rows that share a {@code fingerprint} are one issue with several locations. Issues come out confirmed before unproven
 * ({@code needs_validation}), then by severity from critical to informational, then in the order they first appear. The file is
 * read as UTF-8 (a byte order mark is ignored) with full RFC 4180 quoting: commas, doubled quotes and line breaks inside quoted
 * cells, and CRLF. The single quote that the writer puts in front of a cell a spreadsheet could run as a formula is taken off again.
 *
 * <p>Anything that is not a valid audit CSV stops the tool with a message that names the problem and prints no issue: a missing
 * required column ({@code file}, {@code line}, {@code fingerprint}, {@code verdict}), a row with the wrong number of cells, a line
 * that is not a number, a verdict or severity that is not known, or rows of one issue that disagree about its verdict. Exit code 0
 * means the list is complete and valid; 2 means it is not.
 *
 * <p>Plain Java on purpose: the tool runs as a single source file with no dependencies, on the kit's JDK 25.
 */
public final class IssueList {

    private static final List<String> REQUIRED = List.of("file", "line", "fingerprint", "verdict");
    private static final List<String> SEVERITIES = List.of("critical", "high", "medium", "low", "informational");
    private static final List<String> VERDICTS = List.of("confirmed", "needs_validation");
    /** A cell longer than this is cut, with a marker that says by how much: a report cannot make the output unbounded. */
    private static final int MAX_CELL = 20_000;

    /** One place in the code. */
    record Location(String file, int line, String note) {
    }

    /** One issue: its rows grouped by fingerprint. */
    record Issue(String fingerprint, String title, String verdict, String severity, int order, List<Location> locations,
            Map<String, String> details) {

        String label() {
            return severity.isEmpty() ? verdict : "%s/%s".formatted(severity, verdict);
        }
    }

    /**
     * What is in the file.
     *
     * @param commit the audited commit: the id when the cell holds one (alone, or as {@code commit=<id>, dirty=<bool>}, which is how the
     *               audit tool renders the reviewed source reference), otherwise the cell as written; empty when the CSV names none
     * @param dirty  whether the audited working tree had uncommitted changes, when the cell says; null when it does not
     */
    record Report(String commit, Boolean dirty, List<Issue> issues, int rows) {
    }

    private static final java.util.regex.Pattern BARE_COMMIT = java.util.regex.Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final java.util.regex.Pattern LABELLED_COMMIT = java.util.regex.Pattern
            .compile("(?:^|[,;\\s])commit=([0-9a-fA-F]{7,40})(?=$|[,;\\s])");
    private static final java.util.regex.Pattern DIRTY = java.util.regex.Pattern
            .compile("(?:^|[,;\\s])dirty=(true|false)(?=$|[,;\\s])", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The file is not a valid audit CSV. */
    static final class InvalidReport extends Exception {

        private static final long serialVersionUID = 1L;

        InvalidReport(String message) {
            super(message, null, false, false);
        }
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        boolean json = args.length == 2 && args[1].equals("--json");
        if (args.length == 0 || args.length > 2 || (args.length == 2 && !json) || args[0].startsWith("--")) {
            err.println("usage: java IssueList.java <issues.csv> [--json]");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        try {
            Report report = read(file);
            out.println(json ? asJson(file, report) : asText(file, report));
        } catch (InvalidReport | IOException e) {
            err.println("IssueList: %s: %s".formatted(file, e.getMessage()));
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Reading

    static Report read(Path file) throws IOException, InvalidReport {
        byte[] bytes = Files.readAllBytes(file);
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = parse(text);
        if (records.isEmpty()) {
            throw new InvalidReport("the file is empty");
        }
        List<String> header = records.getFirst().stream().map(String::strip).toList();
        List<String> missing = REQUIRED.stream().filter(column -> !header.contains(column)).toList();
        if (!missing.isEmpty()) {
            throw new InvalidReport("not an audit CSV: the column%s %s %s missing"
                    .formatted(missing.size() == 1 ? "" : "s", String.join(", ", missing), missing.size() == 1 ? "is" : "are"));
        }
        if (records.size() == 1) {
            throw new InvalidReport("the file has a header and no rows");
        }
        Map<String, Issue> issues = new LinkedHashMap<>();
        String commit = "";
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            int rowNumber = i + 1;
            if (cells.size() != header.size()) {
                throw new InvalidReport("row %d has %d cells but the header has %d".formatted(rowNumber, cells.size(), header.size()));
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), clean(cells.get(c)));
            }
            int line = lineOf(row.get("line"), rowNumber);
            String verdict = row.get("verdict");
            if (!VERDICTS.contains(verdict)) {
                throw new InvalidReport("row %d: the verdict '%s' is not one of %s".formatted(rowNumber, shown(verdict), VERDICTS));
            }
            String severity = row.getOrDefault("severity", "");
            if (!severity.isEmpty() && !SEVERITIES.contains(severity)) {
                throw new InvalidReport("row %d: the severity '%s' is not one of %s".formatted(rowNumber, shown(severity), SEVERITIES));
            }
            String fingerprint = row.get("fingerprint");
            if (fingerprint.isEmpty() || row.get("file").isEmpty()) {
                throw new InvalidReport("row %d has no %s".formatted(rowNumber, fingerprint.isEmpty() ? "fingerprint" : "file"));
            }
            if (commit.isEmpty()) {
                commit = row.getOrDefault("commit", "");
            }
            Location location = new Location(row.get("file"), line, row.getOrDefault("location_note", ""));
            Issue existing = issues.get(fingerprint);
            if (existing == null) {
                Map<String, String> details = new LinkedHashMap<>();
                for (String key : List.of("confidence", "description", "root_cause", "trace", "remediation", "blockers",
                        "validation_plan", "attack_class")) {
                    if (row.containsKey(key)) {
                        details.put(key, row.get(key));
                    }
                }
                issues.put(fingerprint, new Issue(fingerprint, row.getOrDefault("title", ""), verdict, severity, issues.size(),
                        new ArrayList<>(List.of(location)), details));
            } else {
                if (!existing.verdict().equals(verdict)) {
                    throw new InvalidReport("the rows of the issue %s disagree about its verdict (%s and %s)"
                            .formatted(fingerprint, existing.verdict(), verdict));
                }
                existing.locations().add(location);
            }
        }
        List<Issue> ordered = issues.values().stream().sorted(Comparator
                .comparingInt((Issue issue) -> VERDICTS.indexOf(issue.verdict()))
                .thenComparingInt(issue -> issue.severity().isEmpty() ? SEVERITIES.size() : SEVERITIES.indexOf(issue.severity()))
                .thenComparingInt(Issue::order)).toList();
        String cell = commit.strip();
        java.util.regex.Matcher labelled = LABELLED_COMMIT.matcher(cell);
        java.util.regex.Matcher flag = DIRTY.matcher(cell);
        String id = BARE_COMMIT.matcher(cell).matches() ? cell : labelled.find() ? labelled.group(1) : cell;
        Boolean dirty = flag.find() ? Boolean.valueOf(flag.group(1).toLowerCase()) : null;
        return new Report(id, dirty, ordered, records.size() - 1);
    }

    private static int lineOf(String value, int rowNumber) throws InvalidReport {
        try {
            int line = Integer.parseInt(value.strip());
            if (line >= 1) {
                return line;
            }
        } catch (NumberFormatException e) {
            // reported below with the value
        }
        throw new InvalidReport("row %d: the line '%s' is not a number from 1 up".formatted(rowNumber, shown(value)));
    }

    /** Takes the formula guard off a cell and cuts a cell that is too long. */
    static String clean(String cell) {
        String text = cell;
        if (text.startsWith("'")) {
            String rest = text.substring(1).stripLeading();
            if (!rest.isEmpty() && "=+-@\t\r".indexOf(rest.charAt(0)) >= 0) {
                text = text.substring(1);
            }
        }
        return text.length() > MAX_CELL
                ? "%s [cut: %d more characters]".formatted(text.substring(0, MAX_CELL), text.length() - MAX_CELL) : text;
    }

    /** A value for a message: control characters replaced and the length limited. */
    private static String shown(String value) {
        String text = value.replaceAll("\\p{Cntrl}", "?");
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }

    /** RFC 4180: records of cells; a quoted cell may hold commas, doubled quotes and line breaks. Empty records are skipped. */
    static List<List<String>> parse(String text) throws InvalidReport {
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean cellStarted = false;
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
                continue;
            }
            switch (c) {
                case '"' -> {
                    if (cell.length() > 0) {
                        throw new InvalidReport("a quote in the middle of a cell (record %d)".formatted(records.size() + 1));
                    }
                    quoted = true;
                    cellStarted = true;
                }
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    cellStarted = true;
                }
                case '\r', '\n' -> {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    if (cellStarted || cell.length() > 0 || !row.isEmpty()) {
                        row.add(cell.toString());
                        records.add(row);
                    }
                    row = new ArrayList<>();
                    cell.setLength(0);
                    cellStarted = false;
                }
                default -> cell.append(c);
            }
        }
        if (quoted) {
            throw new InvalidReport("a quoted cell is never closed (record %d)".formatted(records.size() + 1));
        }
        if (cellStarted || cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            records.add(row);
        }
        return records;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Writing

    static String asText(Path file, Report report) {
        long confirmed = report.issues().stream().filter(issue -> issue.verdict().equals("confirmed")).count();
        long locations = report.issues().stream().mapToLong(issue -> issue.locations().size()).sum();
        String state = report.dirty() == null ? "" : report.dirty() ? " (the audited working tree had uncommitted changes)"
                : " (clean working tree)";
        String heading = "%d issues, %d locations (%d confirmed, %d needs_validation); audited commit %s%s".formatted(
                report.issues().size(), locations, confirmed, report.issues().size() - confirmed,
                report.commit().isEmpty() ? "(none named)" : report.commit(), report.commit().isEmpty() ? "" : state);
        var out = new ArrayList<String>();
        out.add(heading);
        int number = 1;
        for (Issue issue : report.issues()) {
            out.add("");
            out.add("%d. [%s] %s  (%s)".formatted(number++, issue.label(), issue.title(), issue.fingerprint()));
            out.add("   locations: %s".formatted(issue.locations().stream()
                    .map(location -> "%s:%d".formatted(location.file(), location.line())).collect(Collectors.joining(", "))));
            issue.locations().stream().filter(location -> !location.note().isEmpty())
                    .forEach(location -> out.add("   note (%s:%d): %s".formatted(location.file(), location.line(), indent(location.note()))));
            issue.details().forEach((key, value) -> {
                if (!value.isEmpty()) {
                    out.add("   %s: %s".formatted(key.replace('_', ' '), indent(value)));
                }
            });
        }
        return String.join("\n", out);
    }

    /** Continuation lines of a multi-line value line up under the first. */
    private static String indent(String value) {
        return value.strip().lines().collect(Collectors.joining("\n      "));
    }

    static String asJson(Path file, Report report) {
        String issues = report.issues().stream().map(issue -> {
            String locations = issue.locations().stream().map(location -> "{\"file\":%s,\"line\":%d,\"note\":%s}"
                    .formatted(quote(location.file()), location.line(), quote(location.note()))).collect(Collectors.joining(","));
            String details = issue.details().entrySet().stream().map(e -> "%s:%s".formatted(quote(e.getKey()), quote(e.getValue())))
                    .collect(Collectors.joining(","));
            return "{\"fingerprint\":%s,\"title\":%s,\"verdict\":%s,\"severity\":%s,\"locations\":[%s],%s}".formatted(
                    quote(issue.fingerprint()), quote(issue.title()), quote(issue.verdict()), quote(issue.severity()), locations,
                    details);
        }).collect(Collectors.joining(","));
        return "{\"file\":%s,\"commit\":%s,\"dirty\":%s,\"rows\":%d,\"issues\":[%s]}".formatted(quote(file.toString()),
                quote(report.commit()), report.dirty() == null ? "null" : report.dirty().toString(), report.rows(), issues);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
