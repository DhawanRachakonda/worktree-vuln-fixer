package dev.subscriber.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.config.SubscriberProperties.Runner;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LauncherSelectionTest {

    private final SessionLauncher headless = (id, repo, agent) -> null;
    private final SessionLauncher window = (id, repo, agent) -> null;

    @Test
    void aWindowIsUsedOnWindowsWhenAskedFor() {
        assertThat(LauncherConfiguration.select(Runner.WINDOW, true, headless, window)).isSameAs(window);
    }

    @Test
    void headlessIsUsedWhenAskedForOnAnyPlatform() {
        assertThat(LauncherConfiguration.select(Runner.HEADLESS, true, headless, window)).isSameAs(headless);
        assertThat(LauncherConfiguration.select(Runner.HEADLESS, false, headless, window)).isSameAs(headless);
    }

    @Test
    void askingForAWindowWhereNoneCanBeOpenedStopsTheStartInsteadOfFallingBack() {
        assertThatThrownBy(() -> LauncherConfiguration.select(Runner.WINDOW, false, headless, window))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("subscriber.runner=window");
    }

    @Test
    void theWindowLauncherRefusesToRunWhereNoWindowCanBeOpened() {
        if (HeadlessLauncher.isWindows()) {
            return;
        }
        WindowLauncher launcher = new WindowLauncher(null, null, null);
        assertThatThrownBy(() -> launcher.launch("11111111-2222-3333-4444-555555555555", Path.of("x"), "a"))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("can only be opened on Windows");
    }
}
