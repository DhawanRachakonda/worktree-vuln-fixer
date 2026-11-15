package dev.intel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.intel.IntelServiceApplication;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class AccessGuardTest {

    private static final String TOKEN = "a-long-random-access-token-1";

    // ------------------------------------------------------------------ the rule

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.0.0.5", "::1", "localhost", " 127.0.0.1 "})
    void loopbackNeedsNoToken(String address) {
        assertThatCode(() -> AccessGuard.verify(address, false)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "192.168.1.10", "10.0.0.5", "172.16.4.4", "::", "2001:db8::1", ""})
    void anythingBeyondThisMachineNeedsAToken(String address) {
        assertThatThrownBy(() -> AccessGuard.verify(address, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intel.access.token must be set").hasMessageContaining("INTEL_ACCESS_TOKEN");
    }

    @Test
    void anUnsetBindAddressMeansEveryInterfaceAndNeedsAToken() {
        assertThatThrownBy(() -> AccessGuard.verify(null, false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("(all interfaces)").hasMessageContaining("intel.access.token must be set");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "192.168.1.10", "::", "127.0.0.1"})
    void aTokenAllowsAnyBindAddress(String address) {
        assertThatCode(() -> AccessGuard.verify(address, true)).doesNotThrowAnyException();
        assertThatCode(() -> AccessGuard.verify(null, true)).doesNotThrowAnyException();
    }

    @Test
    void anUnresolvableBindAddressIsAStartupError() {
        assertThatThrownBy(() -> AccessGuard.verify("no-such-host.invalid", true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be resolved");
    }

    @Test
    void theMessageNeverContainsTheToken() {
        assertThatThrownBy(() -> AccessGuard.verify("0.0.0.0", false)).hasMessageNotContaining(TOKEN);
    }

    // ------------------------------------------------------------------ the running service

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static ConfigurableApplicationContext start(Path dataDir, int port, String... extra) {
        String[] base = {"--server.port=" + port, "--intel.data-dir=" + dataDir.toAbsolutePath().toString()
                .replace('\\', '/'), "--jobrunr.background-job-server.enabled=false", "--intel.schedule.enabled=false",
                "--spring.main.banner-mode=off"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return new SpringApplicationBuilder(IntelServiceApplication.class).web(WebApplicationType.SERVLET).run(args);
    }

    private static boolean reachable(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A routable address of this machine other than loopback, or null when it has none. */
    private static InetAddress lanAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return address;
                }
            }
        }
        return null;
    }

    @Test
    void byDefaultTheServiceListensOnTheLoopbackAddressOnly(@TempDir Path dataDir) throws Exception {
        InetAddress lan = lanAddress();
        assumeTrue(lan != null, "this machine has no non-loopback IPv4 address to probe");
        int port = freePort();

        try (ConfigurableApplicationContext context = start(dataDir, port)) {
            assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
            assertThat(reachable(InetAddress.getLoopbackAddress(), port)).as("loopback").isTrue();
            assertThat(reachable(lan, port)).as("the machine's own address " + lan.getHostAddress()).isFalse();
        }
    }

    @Test
    void aNonLoopbackBindWithoutATokenStopsStartupAndNothingListens(@TempDir Path dataDir) throws Exception {
        int port = freePort();

        assertThatThrownBy(() -> start(dataDir, port, "--server.address=0.0.0.0")).satisfies(failure -> {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = failure; t != null; t = t.getCause()) {
                chain.append(t.getMessage()).append(" | ");
            }
            assertThat(chain.toString()).contains("intel.access.token must be set").contains("server.address=0.0.0.0");
        });
        assertThat(reachable(InetAddress.getLoopbackAddress(), port)).isFalse();
    }

    @Test
    void aNonLoopbackBindWithATokenStartsAndIsReachableBeyondLoopback(@TempDir Path dataDir) throws Exception {
        InetAddress lan = lanAddress();
        assumeTrue(lan != null, "this machine has no non-loopback IPv4 address to probe");
        int port = freePort();

        try (ConfigurableApplicationContext context = start(dataDir, port, "--server.address=0.0.0.0",
                "--intel.access.token=" + TOKEN)) {
            assertThat(reachable(InetAddress.getLoopbackAddress(), port)).isTrue();
            assertThat(reachable(lan, port)).as("bound to every interface").isTrue();
        }
    }

    @Test
    void theNamedLoopbackHostIsAcceptedWithoutAToken(@TempDir Path dataDir) throws Exception {
        int port = freePort();

        try (ConfigurableApplicationContext context = start(dataDir, port, "--server.address=localhost")) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}
