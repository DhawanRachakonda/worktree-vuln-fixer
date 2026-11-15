package dev.subscriber.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ConsoleModeProcessorTest {

    @Test
    void autoFollowsTheTerminalAndTheOtherValuesForceTheAnswer() {
        assertThat(ConsoleModeProcessor.resolve(null, true)).isTrue();
        assertThat(ConsoleModeProcessor.resolve("", false)).isFalse();
        assertThat(ConsoleModeProcessor.resolve("auto", true)).isTrue();
        assertThat(ConsoleModeProcessor.resolve("AUTO", false)).isFalse();
        assertThat(ConsoleModeProcessor.resolve("true", true)).isTrue();
        assertThat(ConsoleModeProcessor.resolve(" True ", true)).isTrue();
        assertThat(ConsoleModeProcessor.resolve("false", true)).isFalse();
    }

    @Test
    void anyOtherValueStopsTheStartNamingTheSetting() {
        assertThatThrownBy(() -> ConsoleModeProcessor.resolve("yes", true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscriber.console.enabled").hasMessageContaining("auto, true or false").hasMessageContaining("'yes'");
        assertThatThrownBy(() -> ConsoleModeProcessor.resolve("a\u001b[2Jb", true)).hasMessageNotContaining("\u001b");
    }

    @Test
    void whenOffTheShellStaysOffAndLoggingIsUntouched(@TempDir Path data) {
        MockEnvironment environment = new MockEnvironment().withProperty("subscriber.console.enabled", "false")
                .withProperty("subscriber.data-dir", data.toString());
        new ConsoleModeProcessor().apply(environment, true);

        assertThat(environment.getProperty("spring.shell.interactive.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("logging.file.name")).isNull();
        assertThat(environment.getProperty("logging.console.enabled")).isNull();
    }

    @Test
    void whenOnTheShellRunsAndLogsGoToAFileInTheDataDirectory(@TempDir Path data) {
        MockEnvironment environment = new MockEnvironment().withProperty("subscriber.console.enabled", "true")
                .withProperty("subscriber.data-dir", data.toString());
        new ConsoleModeProcessor().apply(environment, true);

        assertThat(environment.getProperty("spring.shell.interactive.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("logging.console.enabled", Boolean.class)).isFalse();
        assertThat(Path.of(environment.getProperty("logging.file.name"))).isEqualTo(data.toAbsolutePath().normalize().resolve("engine.log"));
        assertThat(environment.getProperty("spring.shell.history.enabled", Boolean.class)).as("no history file in the working directory")
                .isFalse();
    }

    @Test
    void forcingTheConsoleOnWithoutATerminalStopsTheStartWithAReason() {
        assertThatThrownBy(() -> ConsoleModeProcessor.resolve("true", false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscriber.console.enabled=true needs a terminal");
    }
}
