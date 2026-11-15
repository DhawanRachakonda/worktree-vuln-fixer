package dev.subscriber.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.config.SubscriberProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionFoldersTest {

    @TempDir
    Path root;

    static SubscriberProperties properties(Path root, SubscriberProperties.Runner runner) {
        return new SubscriberProperties(root.resolve("data").toString().replace('\\', '/'), List.of(root.toString()), List.of(),
                runner, 1, new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
    }

    private SessionFolders folders() {
        return new SessionFolders(properties(root, SubscriberProperties.Runner.AUTO));
    }

    @Test
    void aDataDirectoryWithAWindowsShortNameIsAccepted() {
        // GitHub's Windows runners (and some user profiles) have 8.3 names such as C:\\Users\\RUNNER~1\\AppData\\Local\\Temp
        SubscriberProperties props = new SubscriberProperties("C:/Users/RUNNER~1/AppData/Local/Temp/junit-1/data", List.of(root.toString()),
                List.of(), SubscriberProperties.Runner.AUTO, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null));
        assertThat(props.dataDir()).contains("RUNNER~1");
        assertThatThrownBy(() -> new SubscriberProperties("C:/data;calc", List.of(root.toString()), List.of(),
                SubscriberProperties.Runner.AUTO, 1,
                new SubscriberProperties.Copilot("copilot", SubscriberProperties.PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null), new SubscriberProperties.Access(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFolderOfASessionIsUnderTheDataDirectoryAndOnlyForARealSessionId() {
        String id = UUID.randomUUID().toString();
        assertThat(folders().folder(id)).isEqualTo(root.resolve("data").resolve("sessions").resolve(id).toAbsolutePath().normalize());
        for (String bad : List.of("..", "../x", "a/b", "C:\\x", "", "not-a-uuid", id + "/..", id.toUpperCase())) {
            assertThatThrownBy(() -> folders().folder(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> folders().folder(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSnapshotIsAByteForByteCopyThatALaterChangeOfTheOriginalCannotAffect() throws IOException {
        byte[] content = new byte[] {'f', 'i', 'l', 'e', ',', '\r', '\n', 0, (byte) 0xC3, (byte) 0xA9, '\n', (byte) 0xff};
        Path original = root.resolve("issues.csv");
        Files.write(original, content);
        String id = UUID.randomUUID().toString();

        Path snapshot = folders().snapshot(id, original);
        assertThat(snapshot).isEqualTo(folders().folder(id).resolve("report").resolve("issues.csv"));
        assertThat(Files.readAllBytes(snapshot)).isEqualTo(content);

        Files.writeString(original, "a new audit overwrote the report");
        assertThat(Files.readAllBytes(snapshot)).as("the snapshot did not change").isEqualTo(content);
        assertThat(folders().folder(id).resolve("logs")).isDirectory();
    }

    @Test
    void aReportLargerThanTheLimitIsRefusedAndLeavesNothingBehind() throws IOException {
        Path big = root.resolve("big.csv");
        Files.write(big, new byte[(int) (SessionFolders.MAX_REPORT_BYTES + 1)]);
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> folders().snapshot(id, big)).isInstanceOf(IOException.class).hasMessageContaining("larger than 20 MiB");
        assertThat(folders().folder(id)).doesNotExist();
    }

    @Test
    void theSnapshotNameKeepsLettersAndDigitsAndReplacesEverythingElse() {
        assertThat(SessionFolders.safeName("issues.csv")).isEqualTo("issues.csv");
        assertThat(SessionFolders.safeName("my report (1).csv")).isEqualTo("my report _1_.csv");
        assertThat(SessionFolders.safeName("r&d%^'x.json")).isEqualTo("r_d___x.json");
        assertThat(SessionFolders.safeName("..")).isEqualTo("report");
        assertThat(SessionFolders.safeName("   ")).isEqualTo("report");
        assertThat(SessionFolders.safeName("é.csv")).isEqualTo("_.csv");
        assertThat(SessionFolders.safeName("x".repeat(300))).hasSize(100);
    }

    @Test
    void aReportWithAnOddNameStillGetsASafeSnapshotName() throws IOException {
        Path odd = root.resolve("a&b %1 ^'.csv");
        Files.writeString(odd, "x");
        Path snapshot = folders().snapshot(UUID.randomUUID().toString(), odd);
        assertThat(snapshot.getFileName().toString()).isEqualTo("a_b _1 __.csv");
        assertThat(snapshot).hasContent("x");
    }
}
