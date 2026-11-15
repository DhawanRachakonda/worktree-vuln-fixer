package dev.intel.feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeTest {

    @Test
    void printableAsciiPassesThroughUnchanged() {
        assertThat(Safe.text("application/rss+xml", 60)).isEqualTo("application/rss+xml");
        assertThat(Safe.text("https://radar.cloudflare.com/security/zero-wire/rss?x=1&y=2", 200))
                .isEqualTo("https://radar.cloudflare.com/security/zero-wire/rss?x=1&y=2");
    }

    @Test
    void controlCharactersAndLineBreaksBecomeQuestionMarks() {
        assertThat(Safe.text("a\nb\rc\td\u0000e\u001b[31mf", 100)).isEqualTo("a?b?c?d?e?[31mf");
        assertThat(Safe.text("line one\r\nline two", 100)).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void nonAsciiTextBecomesQuestionMarks() {
        assertThat(Safe.text("héllo 世界 😀", 100)).isEqualTo("h?llo ?? ??");
    }

    @Test
    void longTextIsCappedAndMarked() {
        String result = Safe.text("x".repeat(500), 40);

        assertThat(result).hasSize(43).endsWith("...").startsWith("x".repeat(40));
        assertThat(Safe.text("x".repeat(40), 40)).as("exactly at the cap").isEqualTo("x".repeat(40));
    }

    @Test
    void nothingIsReportedAsNone() {
        assertThat(Safe.text(null, 10)).isEqualTo("(none)");
        assertThat(Safe.text("", 10)).isEmpty();
    }
}
