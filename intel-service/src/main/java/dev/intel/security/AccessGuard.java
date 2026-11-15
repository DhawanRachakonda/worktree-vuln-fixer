package dev.intel.security;

import dev.intel.config.IntelProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the MCP endpoint would be reachable beyond this machine without a token.
 *
 * <p>The service binds to {@code 127.0.0.1} unless {@code server.address} says otherwise. A bind address that is not a
 * loopback address (including "unset", which means every interface) exposes the security data to the network, so it is
 * only accepted together with a configured {@code intel.access.token}. The check runs while the application context is
 * created, before the web server accepts a single connection.
 */
@Component
public class AccessGuard {

    private static final Logger log = LoggerFactory.getLogger(AccessGuard.class);

    public AccessGuard(Environment environment, IntelProperties properties) {
        verify(environment.getProperty("server.address"), properties.access().tokenConfigured());
    }

    static void verify(String bindAddress, boolean tokenConfigured) {
        boolean loopback = isLoopback(bindAddress);
        if (!loopback && !tokenConfigured) {
            throw new IllegalStateException("intel.access.token must be set when the service is reachable beyond this "
                    + "machine (server.address=" + describe(bindAddress) + "): bind to 127.0.0.1 or set "
                    + "INTEL_ACCESS_TOKEN");
        }
        log.info("MCP access: bound to {}, bearer token {}", describe(bindAddress),
                tokenConfigured ? "required" : "not configured (loopback only)");
    }

    private static boolean isLoopback(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(bindAddress.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("server.address '" + bindAddress + "' cannot be resolved", e);
        }
    }

    private static String describe(String bindAddress) {
        return bindAddress == null || bindAddress.isBlank() ? "(all interfaces)" : bindAddress.strip();
    }
}
