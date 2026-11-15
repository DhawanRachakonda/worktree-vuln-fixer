package dev.intel.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class IntelPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(IntelProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaults() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            IntelProperties p = ctx.getBean(IntelProperties.class);
            assertThat(p.dataDir()).isEqualTo("./data");
            assertThat(p.feed().url()).isEqualTo("https://radar.cloudflare.com/security/zero-wire/rss");
            assertThat(p.feed().allowedHosts()).containsExactly("radar.cloudflare.com");
            assertThat(p.feed().maxBytes()).isEqualTo(DataSize.ofMegabytes(5));
            assertThat(p.feed().connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(p.feed().totalTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.feed().maxRedirects()).isEqualTo(3);
            assertThat(p.schedule().cron()).isEqualTo("0 3 * * *");
            assertThat(p.schedule().zoneId().getId()).isEqualTo("UTC");
            assertThat(p.schedule().catchUpAfter()).isEqualTo(Duration.ofHours(24));
            assertThat(p.schedule().enabled()).isTrue();
            assertThat(p.freshness().staleAfter()).isEqualTo(Duration.ofHours(36));
            assertThat(p.access().tokenConfigured()).isFalse();
        });
    }

    @Test
    void overrides() {
        runner.withPropertyValues(
                "intel.data-dir=/var/lib/intel",
                "intel.feed.url=https://feeds.example.org/rss",
                "intel.feed.allowed-hosts=feeds.example.org,Backup.Example.org",
                "intel.feed.max-bytes=1MB",
                "intel.feed.total-timeout=45s",
                "intel.schedule.cron=30 1 * * *",
                "intel.schedule.zone=Asia/Kolkata",
                "intel.freshness.stale-after=48h",
                "intel.access.token=s3cret").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            IntelProperties p = ctx.getBean(IntelProperties.class);
            assertThat(p.dataDir()).isEqualTo("/var/lib/intel");
            assertThat(p.feed().url()).isEqualTo("https://feeds.example.org/rss");
            assertThat(p.feed().allowedHosts()).containsExactly("feeds.example.org", "backup.example.org");
            assertThat(p.feed().maxBytes()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(p.feed().totalTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(p.schedule().cron()).isEqualTo("30 1 * * *");
            assertThat(p.schedule().zoneId().getId()).isEqualTo("Asia/Kolkata");
            assertThat(p.freshness().staleAfter()).isEqualTo(Duration.ofHours(48));
            assertThat(p.access().tokenConfigured()).isTrue();
            assertThat(p.access().token()).isEqualTo("s3cret");
        });
    }

    @Test
    void blankTokenMeansNoToken() {
        runner.withPropertyValues("intel.access.token=   ").run(ctx ->
                assertThat(ctx.getBean(IntelProperties.class).access().tokenConfigured()).isFalse());
    }

    @Test
    void missingFeedUrlStopsStartup() {
        runner.withPropertyValues("intel.feed.url=").run(ctx -> assertFailureMentions(ctx.getStartupFailure(),
                "intel.feed.url must not be blank"));
    }

    @Test
    void nonHttpsFeedUrlStopsStartup() {
        runner.withPropertyValues("intel.feed.url=http://radar.cloudflare.com/security/zero-wire/rss")
                .run(ctx -> assertFailureMentions(ctx.getStartupFailure(), "intel.feed.url must use https"));
    }

    @Test
    void feedHostOutsideAllowlistStopsStartup() {
        runner.withPropertyValues("intel.feed.url=https://evil.example.net/rss")
                .run(ctx -> assertFailureMentions(ctx.getStartupFailure(), "not in intel.feed.allowed-hosts"));
    }

    @Test
    void invalidCronStopsStartup() {
        runner.withPropertyValues("intel.schedule.cron=not a cron")
                .run(ctx -> assertFailureMentions(ctx.getStartupFailure(), "intel.schedule.cron"));
    }

    @Test
    void invalidZoneStopsStartup() {
        runner.withPropertyValues("intel.schedule.zone=Mars/Olympus")
                .run(ctx -> assertFailureMentions(ctx.getStartupFailure(), "intel.schedule.zone"));
    }

    @Test
    void nonPositiveLimitsStopStartup() {
        runner.withPropertyValues("intel.feed.max-bytes=0B")
                .run(ctx -> assertFailureMentions(ctx.getStartupFailure(), "intel.feed.max-bytes"));
        runner.withPropertyValues("intel.freshness.stale-after=0s")
                .run(ctx -> assertFailureMentions(ctx.getStartupFailure(), "intel.freshness.stale-after"));
    }

    private static void assertFailureMentions(Throwable failure, String text) {
        assertThat(failure).as("startup failure").isNotNull();
        Throwable t = failure;
        StringBuilder chain = new StringBuilder();
        while (t != null) {
            chain.append(t.getMessage()).append(" | ");
            t = t.getCause();
        }
        assertThat(chain.toString()).contains(text);
    }
}
