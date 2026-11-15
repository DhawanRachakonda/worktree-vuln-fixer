package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compares generated text with a reviewed file in {@code src/test/resources/golden}. A missing golden file is written and the test
 * fails once, so that its content is reviewed by a person before it counts as the expected result.
 */
final class GoldenFiles {

    private static final Path DIR = Path.of("src", "test", "resources", "golden");

    private GoldenFiles() {
    }

    static void assertMatches(String actual, String name) throws IOException {
        Path file = DIR.resolve(name);
        if (!Files.exists(file)) {
            Files.createDirectories(DIR);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            throw new AssertionError("Golden file " + file + " did not exist and was created: review it, then run the test again");
        }
        assertThat(normalize(actual)).as("generated text against %s", file).isEqualTo(normalize(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
