package dev.subscriber.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.subscriber.SubscriberEngineApplication;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class AccessGuardTest {

    @Test
    void loopbackBindsNeedNoToken() {
        for (String address : new String[] {"127.0.0.1", "localhost", "::1", " 127.0.0.1 "}) {
            assertThatCode(() -> AccessGuard.verify(address, false)).as(address).doesNotThrowAnyException();
        }
    }

    @Test
    void anythingBeyondThisMachineNeedsAToken() {
        for (String address : new String[] {null, "", "  ", "0.0.0.0", "::", "192.168.1.20", "10.0.0.5"}) {
            assertThatThrownBy(() -> AccessGuard.verify(address, false)).as(String.valueOf(address))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("subscriber.access.token")
                    .hasMessageContaining("SUBSCRIBER_ACCESS_TOKEN");
            assertThatCode(() -> AccessGuard.verify(address, true)).as(address + " with token").doesNotThrowAnyException();
        }
    }

    @Test
    void anAddressThatCannotBeResolvedIsRefusedNotTreatedAsLoopback() {
        assertThatThrownBy(() -> AccessGuard.verify("no-such-host.invalid", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AccessGuard.verify("no-such-host.invalid", false)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWholeApplicationRefusesToStartOnARemoteBindWithoutAToken() throws IOException {
        Path data = Files.createTempDirectory("guard-data-");
        Path root = Files.createTempDirectory("guard-root-");
        String[] common = {"--subscriber.data-dir=" + data.toString().replace('\\', '/'),
                "--subscriber.allowed-roots=" + root.toString().replace('\\', '/'), "--subscriber.copilot.permission-mode=allow-all-tools", "--subscriber.nats.url=nats://127.0.0.1:1",
                "--server.port=0", "--spring.main.banner-mode=off"};
        assertThatThrownBy(() -> start(with(common, "--server.address=0.0.0.0"))).hasStackTraceContaining("subscriber.access.token");
        try (ConfigurableApplicationContext ok = start(with(common, "--server.address=127.0.0.1"))) {
            assertThat(ok.isRunning()).isTrue();
        }
        try (ConfigurableApplicationContext ok = start(with(with(common, "--server.address=0.0.0.0"), "--subscriber.access.token=a-long-enough-token"))) {
            assertThat(ok.isRunning()).isTrue();
        }
    }

    private static ConfigurableApplicationContext start(String[] args) {
        return new SpringApplicationBuilder(SubscriberEngineApplication.class).web(WebApplicationType.SERVLET).run(args);
    }

    private static String[] with(String[] rest, String extra) {
        String[] all = new String[rest.length + 1];
        all[0] = extra;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }
}
