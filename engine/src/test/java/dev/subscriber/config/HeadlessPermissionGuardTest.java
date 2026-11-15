package dev.subscriber.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.config.SubscriberProperties.Runner;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class HeadlessPermissionGuardTest {

    @TempDir
    Path root;

    @Test
    void headlessWithTheDefaultModeIsRefusedAndTheMessageNamesTheSettingAndTheChoices() {
        assertThatThrownBy(() -> HeadlessPermissionGuard.check(Runner.HEADLESS, PermissionMode.ALLOW_ALL_TOOLS, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscriber.copilot.permission-mode")
                .hasMessageContaining("allow-list")
                .hasMessageContaining("allow-all-tools")
                .hasMessageContaining("SUBSCRIBER_COPILOT_PERMISSION_MODE");
    }

    @Test
    void headlessWithAskIsRefusedBecauseNobodyCanAnswerWhetherOrNotItWasSet() {
        for (boolean explicit : new boolean[] {true, false}) {
            assertThatThrownBy(() -> HeadlessPermissionGuard.check(Runner.HEADLESS, PermissionMode.ASK, explicit))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nobody can answer");
        }
    }

    @Test
    void headlessWithAnExplicitAllowAllToolsStartsAndWarnsOnce(CapturedOutput output) {
        assertThatCode(() -> HeadlessPermissionGuard.check(Runner.HEADLESS, PermissionMode.ALLOW_ALL_TOOLS, true))
                .doesNotThrowAnyException();
        assertThat(output.getAll()).containsOnlyOnce("agent runs with every tool allowed and nobody to approve");
    }

    @Test
    void headlessWithTheAllowListStartsWithoutAWarning(CapturedOutput output) {
        assertThatCode(() -> HeadlessPermissionGuard.check(Runner.HEADLESS, PermissionMode.ALLOW_LIST, true)).doesNotThrowAnyException();
        assertThat(output.getAll()).doesNotContain("every tool allowed");
    }

    @Test
    void theWindowRunnerStartsWithAnyMode() {
        for (PermissionMode mode : PermissionMode.values()) {
            assertThatCode(() -> HeadlessPermissionGuard.check(Runner.WINDOW, mode, false)).doesNotThrowAnyException();
        }
    }

    @Test
    void theEnvironmentDecidesWhetherTheModeWasSet() {
        SubscriberProperties headless = properties(Runner.HEADLESS);

        assertThatThrownBy(() -> new HeadlessPermissionGuard(headless, new MockEnvironment()))
                .hasMessageContaining("is not set");
        assertThatThrownBy(() -> new HeadlessPermissionGuard(headless,
                new MockEnvironment().withProperty("subscriber.copilot.permission-mode", "  ")))
                .as("an empty placeholder is not a decision").hasMessageContaining("is not set");
        assertThatCode(() -> new HeadlessPermissionGuard(headless,
                new MockEnvironment().withProperty("subscriber.copilot.permission-mode", "allow-all-tools")))
                .doesNotThrowAnyException();
    }

    @Test
    void anEngineWithTheWindowRunnerStartsWithNothingSet() {
        assertThatCode(() -> new HeadlessPermissionGuard(properties(Runner.WINDOW), new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    private SubscriberProperties properties(Runner runner) {
        return new SubscriberProperties(root.toString(), List.of(root.toString()), List.of(), runner, 1,
                new SubscriberProperties.Copilot("copilot", PermissionMode.ALLOW_ALL_TOOLS),
                new SubscriberProperties.Nats("nats://localhost:4222", null, null, null, null),
                new SubscriberProperties.Access(null));
    }
}
