package dev.intel.config;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.jobrunr.scheduling.cron.CronExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * All service settings. Every value can be overridden by an environment variable (for example
 * {@code INTEL_SCHEDULE_CRON}); invalid values stop the application at startup with a message naming the setting.
 */
@ConfigurationProperties("intel")
public record IntelProperties(
        @DefaultValue("./data") String dataDir,
        @DefaultValue Feed feed,
        @DefaultValue Schedule schedule,
        @DefaultValue Freshness freshness,
        @DefaultValue Access access) {

    public IntelProperties {
        dataDir = requireText(dataDir, "intel.data-dir");
    }

    /** Where and how the feed is fetched. */
    public record Feed(
            @DefaultValue("https://radar.cloudflare.com/security/zero-wire/rss") String url,
            @DefaultValue("radar.cloudflare.com") List<String> allowedHosts,
            @DefaultValue("5MB") DataSize maxBytes,
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("30s") Duration totalTimeout,
            @DefaultValue("3") int maxRedirects,
            @DefaultValue("intel-service/0.1 (+vulnerability feed ingestion)") String userAgent) {

        public Feed {
            url = requireText(url, "intel.feed.url");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("intel.feed.url is not a valid URI: " + url, e);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("intel.feed.url must use https: " + url);
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("intel.feed.url has no host: " + url);
            }
            allowedHosts = allowedHosts == null ? List.of()
                    : allowedHosts.stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).filter(h -> !h.isEmpty())
                            .toList();
            if (allowedHosts.isEmpty()) {
                throw new IllegalArgumentException("intel.feed.allowed-hosts must list at least one host");
            }
            if (!allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "intel.feed.url host '" + uri.getHost() + "' is not in intel.feed.allowed-hosts " + allowedHosts);
            }
            requirePositive(maxBytes.toBytes(), "intel.feed.max-bytes");
            requirePositive(connectTimeout, "intel.feed.connect-timeout");
            requirePositive(totalTimeout, "intel.feed.total-timeout");
            if (maxRedirects < 0 || maxRedirects > 10) {
                throw new IllegalArgumentException("intel.feed.max-redirects must be between 0 and 10");
            }
            userAgent = requireText(userAgent, "intel.feed.user-agent");
        }
    }

    /**
     * When the daily ingestion runs and when a start-up catch-up is needed.
     *
     * @param enabled false turns off the daily job and the start-up catch-up (tests, or running only the MCP tool)
     */
    public record Schedule(
            @DefaultValue("0 3 * * *") String cron,
            @DefaultValue("UTC") String zone,
            @DefaultValue("24h") Duration catchUpAfter,
            @DefaultValue("true") boolean enabled) {

        public Schedule {
            cron = requireText(cron, "intel.schedule.cron");
            try {
                new CronExpression(cron);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("intel.schedule.cron is not a valid cron expression: " + cron, e);
            }
            zone = requireText(zone, "intel.schedule.zone");
            try {
                ZoneId.of(zone);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("intel.schedule.zone is not a valid time zone: " + zone, e);
            }
            requirePositive(catchUpAfter, "intel.schedule.catch-up-after");
        }

        public ZoneId zoneId() {
            return ZoneId.of(zone);
        }
    }

    /** How old the last successful ingestion may be before responses report stale data. */
    public record Freshness(@DefaultValue("36h") Duration staleAfter) {

        public Freshness {
            requirePositive(staleAfter, "intel.freshness.stale-after");
        }
    }

    /** Access protection of the MCP endpoint; a blank token means none is configured. */
    public record Access(@DefaultValue("") String token) {

        public Access {
            token = token == null || token.isBlank() ? null : token;
        }

        public boolean tokenConfigured() {
            return token != null;
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
