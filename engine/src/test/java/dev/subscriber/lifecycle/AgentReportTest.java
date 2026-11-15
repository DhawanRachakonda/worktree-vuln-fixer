package dev.subscriber.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.subscriber.lifecycle.AgentReport.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentReportTest {

    private static final Instant START = Instant.parse("2026-11-10T12:00:00Z");

    @TempDir
    Path repo;

    private Path write(String text, Instant modified) throws IOException {
        Path file = repo.resolve(".appmod/result.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void aReportWrittenAfterTheStartIsFreshAndItsStatusIsRead() throws IOException {
        write("{\"status\": \"succeeded\", \"jobType\": \"security-fix\", \"commits\": [\"a b\"], \"extra\": {\"x\": 1}}",
                START.plusSeconds(60));
        AgentReport.Read r = AgentReport.read(repo, START);
        assertThat(r.kind()).isEqualTo(Kind.FRESH);
        assertThat(r.status()).isEqualTo("succeeded");
    }

    @Test
    void aReportWrittenExactlyAtTheStartCounts() throws IOException {
        write("{\"status\": \"failed\"}", START);
        assertThat(AgentReport.read(repo, START).kind()).isEqualTo(Kind.FRESH);
    }

    @Test
    void aReportFromBeforeTheStartIsStaleAndDoesNotCount() throws IOException {
        write("{\"status\": \"succeeded\"}", START.minusSeconds(1));
        AgentReport.Read r = AgentReport.read(repo, START);
        assertThat(r.kind()).isEqualTo(Kind.STALE);
        assertThat(r.status()).as("a stale report's status is never exposed").isNull();
    }

    @Test
    void noReportAtAllIsMissing() {
        assertThat(AgentReport.read(repo, START).kind()).isEqualTo(Kind.MISSING);
    }

    @Test
    void aDirectoryWhereTheReportShouldBeIsMissing() throws IOException {
        Files.createDirectories(repo.resolve(".appmod/result.json"));
        assertThat(AgentReport.read(repo, START).kind()).isEqualTo(Kind.MISSING);
    }

    @Test
    void aFreshReportThatCannotBeUsedIsUnreadable() throws IOException {
        for (String bad : new String[] {"", "not json", "{\"status\": ", "[]", "{}", "{\"status\": \"\"}", "{\"status\": \"  \"}",
                "{\"status\": 5}", "{\"status\": {\"a\": 1}}"}) {
            write(bad, START.plusSeconds(1));
            assertThat(AgentReport.read(repo, START).kind()).as(bad).isEqualTo(Kind.UNREADABLE);
        }
    }

    @Test
    void aByteOrderMarkAndPaddingAroundTheStatusAreTolerated() throws IOException {
        write("﻿{\"status\": \"  needs_input \"}", START.plusSeconds(1));
        AgentReport.Read r = AgentReport.read(repo, START);
        assertThat(r.kind()).isEqualTo(Kind.FRESH);
        assertThat(r.status()).isEqualTo("needs_input");
    }
}
