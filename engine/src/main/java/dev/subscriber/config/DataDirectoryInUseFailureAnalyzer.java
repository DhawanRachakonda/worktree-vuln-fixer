package dev.subscriber.config;

import java.sql.SQLException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

/**
 * Turns H2's "database may be already in use" failure into a plain explanation of the single-instance rule: the embedded database
 * file can only be opened by one process at a time, so a second engine on the same data directory must stop, naming the directory.
 */
public class DataDirectoryInUseFailureAnalyzer implements FailureAnalyzer {

    /** H2 error code DATABASE_ALREADY_OPEN_1. */
    private static final int H2_DATABASE_ALREADY_OPEN = 90020;

    private final Environment environment;

    public DataDirectoryInUseFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getErrorCode() == H2_DATABASE_ALREADY_OPEN) {
                String dir = environment.getProperty("subscriber.data-dir", "(unknown)");
                return new FailureAnalysis(
                        "The subscriber-engine data directory %s is already in use by another process: %s".formatted(dir, firstLine(sql)),
                        """
                        Only one subscriber-engine can use a data directory at a time. Stop the other instance, or start this \
                        one with a different data directory (SUBSCRIBER_DATA_DIR).""",
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
