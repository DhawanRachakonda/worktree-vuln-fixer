package dev.subscriber.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.MappingIterator;
import tools.jackson.dataformat.csv.CsvMapper;
import tools.jackson.dataformat.csv.CsvSchema;

/**
 * The commit an audit report says it looked at: the {@code commit} column of the first data row of a CSV report. The audit tool writes
 * that cell as the reviewed source reference "as given": a bare commit id, or {@code commit=<id>, dirty=<true|false>} (the form its
 * run metadata produces). Both are read; {@code dirty=true} means the audit saw uncommitted changes, which is reported with the commit. Only that column is
 * bound (into a record, with every other column ignored), and at most the first mebibyte of the file is read, so a huge or hostile
 * report cannot cost more than that. A report that is not a CSV, or whose first row names no usable commit, gives a reason instead
 * of a value; the caller then starts from {@code HEAD} and records the reason.
 */
public final class ReportCommit {

    /**
     * The commit named (lower case, 7 to 40 hexadecimal characters), or why none could be used.
     *
     * @param dirty true when the report says the audited working tree had uncommitted changes
     */
    public record Result(String commit, String problem, boolean dirty) {

        static Result found(String commit, boolean dirty) {
            return new Result(commit, null, dirty);
        }

        static Result none(String problem) {
            return new Result(null, problem, false);
        }

        public boolean usable() {
            return commit != null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(String commit) {
    }

    private static final int MAX_BYTES = 1 << 20;
    private static final Pattern BARE = Pattern.compile("[0-9a-fA-F]{7,40}");
    private static final Pattern LABELLED = Pattern.compile("(?:^|[,;\\s])commit=([0-9a-fA-F]{7,40})(?=$|[,;\\s])");
    private static final Pattern DIRTY = Pattern.compile("(?:^|[,;\\s])dirty=true(?=$|[,;\\s])", Pattern.CASE_INSENSITIVE);
    private static final CsvMapper MAPPER = CsvMapper.builder().build();
    private static final CsvSchema SCHEMA = CsvSchema.emptySchema().withHeader();

    private ReportCommit() {
    }

    public static Result read(Path report) {
        if (report == null || report.getFileName() == null
                || !report.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return Result.none("the report is not a CSV file");
        }
        byte[] bytes;
        try (InputStream in = Files.newInputStream(report)) {
            bytes = in.readNBytes(MAX_BYTES);
        } catch (IOException _) {
            return Result.none("the report could not be read");
        }
        int skip = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text = new String(bytes, skip, bytes.length - skip, StandardCharsets.UTF_8);
        try (MappingIterator<Row> rows = MAPPER.readerFor(Row.class).with(SCHEMA).readValues(text)) {
            if (!rows.hasNext()) {
                return Result.none("the report has no rows");
            }
            String commit = rows.next().commit();
            if (commit == null || commit.isBlank()) {
                return Result.none("the report names no commit in its first row");
            }
            commit = commit.strip();
            if (BARE.matcher(commit).matches()) {
                return Result.found(commit.toLowerCase(Locale.ROOT), false);
            }
            Matcher labelled = LABELLED.matcher(commit);
            return labelled.find() ? Result.found(labelled.group(1).toLowerCase(Locale.ROOT), DIRTY.matcher(commit).find())
                    : Result.none("the report's commit is not a commit id");
        } catch (RuntimeException _) {
            return Result.none("the report is not a readable CSV");
        }
    }
}
