package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExitRecordTest {

    @TempDir
    Path folder;

    private void write(String text) throws IOException {
        Files.writeString(folder.resolve("exit.json"), text);
    }

    @Test
    void aCompleteRecordIsRead() throws IOException {
        write("{\"exitCode\": 7, \"endedAt\": \"2026-11-10T11:41:42.3039413Z\", \"headEnd\": \"abc123\"}");
        ExitRecord r = ExitRecord.read(folder).orElseThrow();
        assertThat(r.exitCode()).isEqualTo(7);
        assertThat(r.endedAt()).isEqualTo(Instant.parse("2026-11-10T11:41:42.3039413Z"));
        assertThat(r.headEnd()).isEqualTo("abc123");
    }

    @Test
    void aNullCommitAndUnknownFieldsAreFine() throws IOException {
        write("{\"exitCode\": 0, \"endedAt\": \"2026-11-10T11:41:42Z\", \"headEnd\": null, \"extra\": 1}");
        ExitRecord r = ExitRecord.read(folder).orElseThrow();
        assertThat(r.exitCode()).isZero();
        assertThat(r.headEnd()).isNull();
    }

    @Test
    void aRecordWithAByteOrderMarkIsStillRead() throws IOException {
        write("﻿{\"exitCode\": 0, \"endedAt\": \"2026-11-10T11:41:42Z\"}");
        // The generated scripts write without a byte order mark, but a hand-made or older record should not be lost.
        assertThat(ExitRecord.read(folder)).isPresent();
    }

    @Test
    void aMissingIncompleteOrUnreadableRecordIsNoRecord() throws IOException {
        assertThat(ExitRecord.read(folder)).isEmpty();
        write("");
        assertThat(ExitRecord.read(folder)).isEmpty();
        write("{\"exitCode\": 0");
        assertThat(ExitRecord.read(folder)).isEmpty();
        write("{\"exitCode\": 0}");
        assertThat(ExitRecord.read(folder)).as("no end time").isEmpty();
        write("{\"exitCode\": \"seven\", \"endedAt\": \"2026-11-10T11:41:42Z\"}");
        assertThat(ExitRecord.read(folder)).isEmpty();
        write("{\"exitCode\": 0, \"endedAt\": \"not a time\"}");
        assertThat(ExitRecord.read(folder)).isEmpty();
        Files.delete(folder.resolve("exit.json"));
        Files.createDirectory(folder.resolve("exit.json"));
        assertThat(ExitRecord.read(folder)).as("a directory is not a record").isEmpty();
    }
}
