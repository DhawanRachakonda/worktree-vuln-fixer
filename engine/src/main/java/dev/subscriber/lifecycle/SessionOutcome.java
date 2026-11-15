package dev.subscriber.lifecycle;

import dev.subscriber.runner.ExitRecord;
import dev.subscriber.store.SessionState;
import java.util.Optional;

/**
 * The final state of a session that ended, worked out from what is on disk. A clean exit proves nothing: a person who quits the
 * window with Ctrl+C was observed to give exit code 0, so a session is only done when the agent's own, fresh report says so.
 *
 * <pre>
 *   no exit record                         INTERRUPTED
 *   exit code not 0                        ERROR
 *   exit 0, fresh report succeeded         DONE
 *   exit 0, fresh report partially_...     PARTIAL
 *   exit 0, fresh report needs_input       NEEDS_INPUT
 *   exit 0, fresh report failed or other   ERROR
 *   exit 0, no report, a stale one, or one that cannot be read    ERROR
 * </pre>
 *
 * @param reportStatus what the agent reported, when it reported one; stored as the session's outcome
 * @param reason       why the session is in an error or interrupted state; null otherwise
 */
public record SessionOutcome(SessionState state, String reportStatus, String reason) {

    /** The width of the outcome column. */
    static final int MAX_STATUS = 30;

    public static SessionOutcome derive(Optional<ExitRecord> exit, AgentReport.Read report) {
        String reported = report.kind() == AgentReport.Kind.FRESH ? clean(report.status()) : null;
        if (exit.isEmpty()) {
            return new SessionOutcome(SessionState.INTERRUPTED, reported,
                    "the session ended without leaving an exit record (the window or its launcher was stopped)");
        }
        int code = exit.get().exitCode();
        if (code != 0) {
            return new SessionOutcome(SessionState.ERROR, reported, "the agent exited with code " + code
                    + (reported == null ? "" : "; its report says " + reported));
        }
        return switch (report.kind()) {
            case MISSING, STALE -> new SessionOutcome(SessionState.ERROR, null, "the agent ended without a report");
            case UNREADABLE -> new SessionOutcome(SessionState.ERROR, null, "the agent's report could not be read");
            case FRESH -> fromStatus(reported);
        };
    }

    private static SessionOutcome fromStatus(String status) {
        return switch (status) {
            case "succeeded" -> new SessionOutcome(SessionState.DONE, status, null);
            case "partially_succeeded" -> new SessionOutcome(SessionState.PARTIAL, status, null);
            case "needs_input" -> new SessionOutcome(SessionState.NEEDS_INPUT, status, null);
            case "failed" -> new SessionOutcome(SessionState.ERROR, status, "the agent reported that it failed");
            default -> new SessionOutcome(SessionState.ERROR, status, "the agent's report has an unknown status: " + status);
        };
    }

    /**
     * A status comes from a file the agent wrote. It is stored in a 30-character column and shown to people, so control characters
     * are replaced and the text is cut here, once, before it goes anywhere.
     */
    static String clean(String text) {
        String cleaned = text.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > MAX_STATUS ? cleaned.substring(0, MAX_STATUS) : cleaned;
    }
}
