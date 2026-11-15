package dev.subscriber.worktree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportCommitTest {

    private static final String SHA = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432";

    @TempDir
    Path dir;

    private Path csv(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }

    @Test
    void theCommitOfTheFirstRowIsReadWhateverTheColumnOrder() throws IOException {
        ReportCommit.Result result = ReportCommit.read(csv("issues.csv",
                "file,line,title,fingerprint,verdict,severity,commit\r\nsrc/a.js,3,Bad,fp1,confirmed,high," + SHA + "\r\n"
                        + "src/b.js,9,Other,fp2,confirmed,low,1111111111111111111111111111111111111111\r\n"));

        assertThat(result.usable()).isTrue();
        assertThat(result.commit()).isEqualTo(SHA);
        assertThat(result.problem()).isNull();
    }

    @Test
    void aQuotedValueAndAShortCommitAndUpperCaseAreAccepted() throws IOException {
        assertThat(ReportCommit.read(csv("a.csv", "commit,file\n\"" + SHA + "\",x\n")).commit()).isEqualTo(SHA);
        assertThat(ReportCommit.read(csv("b.csv", "file,commit\nx,9F8E7D6\n")).commit()).isEqualTo("9f8e7d6");
        assertThat(ReportCommit.read(csv("c.csv", "file,commit\nx,  " + SHA + "  \n")).commit()).isEqualTo(SHA);
    }

    @Test
    void theFormTheAuditToolWritesIsReadAndItsDirtyFlagIsReported() throws IOException {
        ReportCommit.Result clean = ReportCommit.read(csv("clean.csv", "file,commit\nx,\"commit=" + SHA + ", dirty=false\"\n"));
        assertThat(clean.commit()).isEqualTo(SHA);
        assertThat(clean.dirty()).isFalse();

        ReportCommit.Result dirty = ReportCommit.read(csv("dirty.csv", "file,commit\nx,\"commit=" + SHA + ", dirty=true\"\n"));
        assertThat(dirty.commit()).isEqualTo(SHA);
        assertThat(dirty.dirty()).isTrue();

        assertThat(ReportCommit.read(csv("only.csv", "file,commit\nx,commit=" + SHA + "\n")).commit()).isEqualTo(SHA);
        assertThat(ReportCommit.read(csv("other-order.csv", "file,commit\nx,\"dirty=true, commit=9F8E7D6\"\n")).commit()).isEqualTo("9f8e7d6");
        assertThat(ReportCommit.read(csv("bare.csv", "file,commit\nx," + SHA + "\n")).dirty()).as("a bare id says nothing about dirty").isFalse();
    }

    @Test
    void aLabelledValueThatIsNotACommitIsRefused() throws IOException {
        for (String value : new String[] {"ref=main, dirty=false", "commit=main", "commit=", "mycommit=" + SHA, "commit=" + SHA + "zz",
                "commit=--upload-pack=x", "branch=main, commit=abc", "dirty=true"}) {
            ReportCommit.Result result = ReportCommit.read(csv("l.csv", "file,commit\nx,\"" + value + "\"\n"));

            assertThat(result.usable()).as(value).isFalse();
            assertThat(result.problem()).as(value).contains("not a commit id");
        }
    }

    @Test
    void aByteOrderMarkAtTheStartIsIgnored() throws IOException {
        Path file = dir.resolve("bom.csv");
        byte[] body = ("file,commit\r\nx," + SHA + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[body.length + 3];
        all[0] = (byte) 0xEF;
        all[1] = (byte) 0xBB;
        all[2] = (byte) 0xBF;
        System.arraycopy(body, 0, all, 3, body.length);
        Files.write(file, all);

        assertThat(ReportCommit.read(file).commit()).isEqualTo(SHA);
    }

    @Test
    void textAroundTheCommitColumnWithCommasQuotesAndLineBreaksDoesNotConfuseIt() throws IOException {
        ReportCommit.Result result = ReportCommit.read(csv("tricky.csv",
                "file,line,description,commit\r\nx,1,\"a, \"\"quoted\"\" thing\r\nover two lines\"," + SHA + "\r\n"));

        assertThat(result.commit()).isEqualTo(SHA);
    }

    @Test
    void noCommitColumnNoRowsAndAnEmptyValueEachGiveAReason() throws IOException {
        assertThat(ReportCommit.read(csv("nocolumn.csv", "file,line\nx,1\n")).problem()).contains("no commit");
        assertThat(ReportCommit.read(csv("norows.csv", "file,line,commit\n")).problem()).contains("no rows");
        assertThat(ReportCommit.read(csv("empty.csv", "file,commit\nx,\n")).problem()).contains("no commit");
        assertThat(ReportCommit.read(csv("blank.csv", "file,commit\nx,   \n")).problem()).contains("no commit");
        assertThat(ReportCommit.read(csv("zero.csv", "")).usable()).isFalse();
    }

    @Test
    void aValueThatIsNotACommitIdIsRefused() throws IOException {
        for (String value : new String[] {"HEAD", "main", "abc123", "9f8e7d6c5b4a39281706f5e4d3c2b1a0987654321", "--upload-pack=evil",
                "9f8e7d6; rm -rf /", "-1", "9f8e7d6\u001b[31m", "g9f8e7d6"}) {
            ReportCommit.Result result = ReportCommit.read(csv("v.csv", "file,commit\nx,\"" + value.replace("\"", "\"\"") + "\"\n"));

            assertThat(result.usable()).as(value).isFalse();
            assertThat(result.problem()).as(value).contains("not a commit id");
        }
    }

    @Test
    void aFileThatIsNotACsvIsRefusedByNameOrByContent() throws IOException {
        assertThat(ReportCommit.read(csv("report.json", "{\"commit\":\"" + SHA + "\"}")).problem()).contains("not a CSV");
        assertThat(ReportCommit.read(csv("report.txt", "commit\n" + SHA)).problem()).contains("not a CSV");
        assertThat(ReportCommit.read(dir.resolve("missing.csv")).problem()).contains("could not be read");
        assertThat(ReportCommit.read(null).usable()).isFalse();
        assertThat(ReportCommit.read(csv("binary.csv", "\u0000\u0001\u0002 not,really\"")).usable()).isFalse();
    }

    @Test
    void aHugeReportIsOnlyReadAsFarAsItsFirstMebibyte() throws IOException {
        String filler = "x".repeat(2 * 1024 * 1024);
        // The commit is in the first row, which fits in the first mebibyte: still found, though the file is much larger.
        Path large = csv("large.csv", "file,commit\nx," + SHA + "\n" + ("y," + filler + "\n"));
        assertThat(ReportCommit.read(large).commit()).isEqualTo(SHA);
        // The first row itself is larger than the limit: refused, not read to the end.
        Path hostile = csv("hostile.csv", "file,commit\n\"" + filler + "\"," + SHA + "\n");
        assertThat(ReportCommit.read(hostile).usable()).isFalse();
    }
}
