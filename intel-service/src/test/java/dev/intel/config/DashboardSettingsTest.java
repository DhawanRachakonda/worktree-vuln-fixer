package dev.intel.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DashboardSettingsTest {

    @Configuration
    @EnableConfigurationProperties(DashboardSettings.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append(" | ");
        }
        return text.toString();
    }

    @Test
    void theDashboardIsOffByDefaultAndNeedsNoCredentialsThen() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DashboardSettings settings = ctx.getBean(DashboardSettings.class);
            assertThat(settings.enabled()).isFalse();
            assertThat(settings.port()).isEqualTo(8000);
        });
    }

    @Test
    void credentialsAloneDoNotEnableIt() {
        runner.withPropertyValues("jobrunr.dashboard.username=ops", "jobrunr.dashboard.password=short")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void enabledWithAUsernameAndALongEnoughPasswordIsAccepted() {
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.username=ops",
                "jobrunr.dashboard.password=twelve-chars", "jobrunr.dashboard.port=9100").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            DashboardSettings settings = ctx.getBean(DashboardSettings.class);
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.username()).isEqualTo("ops");
            assertThat(settings.port()).isEqualTo(9100);
        });
    }

    @Test
    void enabledWithoutAUsernameStopsStartupNamingTheSetting() {
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.password=twelve-chars")
                .run(ctx -> assertThat(chain(ctx.getStartupFailure()))
                        .contains("jobrunr.dashboard.username must be set"));
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.username=  ",
                "jobrunr.dashboard.password=twelve-chars")
                .run(ctx -> assertThat(chain(ctx.getStartupFailure()))
                        .contains("jobrunr.dashboard.username must be set"));
    }

    @Test
    void enabledWithoutAPasswordStopsStartupNamingTheSetting() {
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.username=ops")
                .run(ctx -> assertThat(chain(ctx.getStartupFailure()))
                        .contains("jobrunr.dashboard.password must be set"));
    }

    @Test
    void aWeakPasswordStopsStartupWithoutEchoingIt() {
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.username=ops",
                "jobrunr.dashboard.password=eleven-char").run(ctx -> {
            String failure = chain(ctx.getStartupFailure());
            assertThat(failure).contains("jobrunr.dashboard.password must be at least 12 characters");
            assertThat(failure).doesNotContain("eleven-char");
        });
    }

    @Test
    void thePasswordBoundaryIsExactlyTwelveCharacters() {
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.username=ops",
                "jobrunr.dashboard.password=123456789012").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("jobrunr.dashboard.enabled=true", "jobrunr.dashboard.username=ops",
                "jobrunr.dashboard.password=12345678901").run(ctx -> assertThat(ctx).hasFailed());
    }
}
