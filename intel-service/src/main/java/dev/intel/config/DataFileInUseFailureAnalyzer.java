package dev.intel.config;

import java.sql.SQLException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Turns H2's "database may be already in use" failure into a plain explanation of the single-instance rule: the
 * embedded database file can only be opened by one process at a time.
 */
public class DataFileInUseFailureAnalyzer implements FailureAnalyzer {

    /** H2 error code DATABASE_ALREADY_OPEN_1. */
    private static final int H2_DATABASE_ALREADY_OPEN = 90020;

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == H2_DATABASE_ALREADY_OPEN) {
                return new FailureAnalysis(
                        "The intel-service data file is already in use by another process: " + firstLine(sql),
                        "Only one intel-service instance can open the data file at a time. Stop the other instance, "
                                + "or start this one with a different data directory (INTEL_DATA_DIR).",
                        failure);
            }
        }
        return null;
    }

    private static String firstLine(Throwable t) {
        String message = t.getMessage();
        if (message == null) {
            return "(no detail)";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
