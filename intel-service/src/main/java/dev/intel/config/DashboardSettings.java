package dev.intel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The guard for JobRunr's operations dashboard. It binds the same keys JobRunr itself reads
 * ({@code jobrunr.dashboard.*}), so operators use JobRunr's documented settings, and refuses to start the service when
 * the dashboard is enabled without credentials.
 *
 * <p>JobRunr's dashboard server listens on every network interface over plain HTTP and cannot be told otherwise, and it
 * serves anonymously when no credentials are set. Enabling it therefore requires a username and a password of at least
 * {@value #MIN_PASSWORD_LENGTH} characters. The password is never part of a message produced here.
 *
 * @param enabled  whether the dashboard is served; off by default
 * @param username the basic-authentication user name
 * @param password the basic-authentication password
 * @param port     the port the dashboard listens on
 */
@ConfigurationProperties("jobrunr.dashboard")
public record DashboardSettings(
        @DefaultValue("false") boolean enabled,
        String username,
        String password,
        @DefaultValue("8000") int port) {

    static final int MIN_PASSWORD_LENGTH = 12;

    public DashboardSettings {
        if (enabled) {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException(
                        "jobrunr.dashboard.username must be set when jobrunr.dashboard.enabled is true");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException(
                        "jobrunr.dashboard.password must be set when jobrunr.dashboard.enabled is true");
            }
            if (password.length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException(
                        "jobrunr.dashboard.password must be at least " + MIN_PASSWORD_LENGTH + " characters");
            }
        }
    }
}
