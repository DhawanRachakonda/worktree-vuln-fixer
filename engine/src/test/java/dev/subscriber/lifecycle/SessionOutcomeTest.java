package dev.subscriber.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.lifecycle.AgentReport.Kind;
import dev.subscriber.runner.ExitRecord;
import dev.subscriber.store.SessionState;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every row of the design's state table. */
class SessionOutcomeTest {

    private static final Instant T = Instant.parse("2026-11-10T12:00:00Z");

    private static Optional<ExitRecord> exit(int code) {
        return Optional.of(new ExitRecord(code, T, "abc"));
    }

    private static AgentReport.Read fresh(String status) {
        return new AgentReport.Read(Kind.FRESH, status);
    }

    private static AgentReport.Read none(Kind kind) {
        return new AgentReport.Read(kind, null);
    }

    @ParameterizedTest(name = "exit 0 and a fresh report saying {0} is {1}")
    @CsvSource({"succeeded, DONE", "partially_succeeded, PARTIAL", "needs_input, NEEDS_INPUT"})
    void aCleanExitWithAFreshReportTakesItsStateFromTheReport(String status, SessionState expected) {
        SessionOutcome o = SessionOutcome.derive(exit(0), fresh(status));
        assertThat(o.state()).isEqualTo(expected);
        assertThat(o.reportStatus()).isEqualTo(status);
        assertThat(o.reason()).isNull();
    }

    @Test
    void aReportThatSaysFailedIsAnError() {
        SessionOutcome o = SessionOutcome.derive(exit(0), fresh("failed"));
        assertThat(o.state()).isEqualTo(SessionState.ERROR);
        assertThat(o.reportStatus()).isEqualTo("failed");
        assertThat(o.reason()).contains("reported that it failed");
    }

    @Test
    void aCleanExitWithoutAUsableReportIsAnErrorNotADone() {
        for (Kind kind : new Kind[] {Kind.MISSING, Kind.STALE}) {
            SessionOutcome o = SessionOutcome.derive(exit(0), none(kind));
            assertThat(o.state()).as(kind.name()).isEqualTo(SessionState.ERROR);
            assertThat(o.reason()).isEqualTo("the agent ended without a report");
            assertThat(o.reportStatus()).isNull();
        }
        SessionOutcome unreadable = SessionOutcome.derive(exit(0), none(Kind.UNREADABLE));
        assertThat(unreadable.state()).isEqualTo(SessionState.ERROR);
        assertThat(unreadable.reason()).contains("could not be read");
    }

    @Test
    void aNonZeroExitIsAnErrorWhateverTheReportSaysAndTheReportIsKept() {
        SessionOutcome plain = SessionOutcome.derive(exit(3), none(Kind.MISSING));
        assertThat(plain.state()).isEqualTo(SessionState.ERROR);
        assertThat(plain.reason()).isEqualTo("the agent exited with code 3");

        SessionOutcome contradicting = SessionOutcome.derive(exit(3), fresh("succeeded"));
        assertThat(contradicting.state()).isEqualTo(SessionState.ERROR);
        assertThat(contradicting.reportStatus()).isEqualTo("succeeded");
        assertThat(contradicting.reason()).contains("code 3").contains("its report says succeeded");

        assertThat(SessionOutcome.derive(exit(-1), none(Kind.MISSING)).reason()).contains("code -1");
    }

    @Test
    void noExitRecordMeansTheSessionWasInterruptedAndAReportIsStillRemembered() {
        SessionOutcome o = SessionOutcome.derive(Optional.empty(), none(Kind.MISSING));
        assertThat(o.state()).isEqualTo(SessionState.INTERRUPTED);
        assertThat(o.reason()).contains("without leaving an exit record");

        SessionOutcome withReport = SessionOutcome.derive(Optional.empty(), fresh("succeeded"));
        assertThat(withReport.state()).as("a report without an exit record does not make a session done")
                .isEqualTo(SessionState.INTERRUPTED);
        assertThat(withReport.reportStatus()).isEqualTo("succeeded");
    }

    @Test
    void anUnknownStatusIsAnErrorAndIsCleanedAndCutBeforeItIsStored() {
        SessionOutcome o = SessionOutcome.derive(exit(0), fresh("weird"));
        assertThat(o.state()).isEqualTo(SessionState.ERROR);
        assertThat(o.reason()).isEqualTo("the agent's report has an unknown status: weird");

        SessionOutcome hostile = SessionOutcome.derive(exit(0), fresh("x\n2026 INFO forged\u001b[2K" + "y".repeat(100)));
        assertThat(hostile.state()).isEqualTo(SessionState.ERROR);
        assertThat(hostile.reportStatus()).hasSizeLessThanOrEqualTo(30).doesNotContain("\n").doesNotContain("\u001b");
        assertThat(hostile.reason()).doesNotContain("\n").doesNotContain("\u001b");
    }
}
