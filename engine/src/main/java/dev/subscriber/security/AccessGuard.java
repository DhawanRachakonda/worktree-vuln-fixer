package dev.subscriber.security;

import dev.subscriber.config.SubscriberProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the read API would be reachable beyond this machine without a token.
 *
 * <p>The engine binds to {@code 127.0.0.1} unless {@code server.address} says otherwise. A bind address that is not a loopback address
 * (including "unset", which means every interface) exposes session records to the network, so it is only accepted together with a
 * configured {@code subscriber.access.token}. The check runs while the application context is created, before the web server accepts
 * a connection.
 */
@Component
public class AccessGuard {

    private static final Logger log = LoggerFactory.getLogger(AccessGuard.class);

    public AccessGuard(Environment environment, SubscriberProperties properties) {
        verify(environment.getProperty("server.address"), properties.access().token() != null);
    }

    static void verify(String bindAddress, boolean tokenConfigured) {
        boolean loopback = isLoopback(bindAddress);
        if (!loopback && !tokenConfigured) {
            throw new IllegalStateException("""
                    subscriber.access.token must be set when the API is reachable beyond this machine \
                    (server.address=%s): bind to 127.0.0.1 or set SUBSCRIBER_ACCESS_TOKEN""".formatted(describe(bindAddress)));
        }
        log.info("Read API: bound to {}, bearer token {}", describe(bindAddress),
                tokenConfigured ? "required" : "not configured (loopback only)");
    }

    private static boolean isLoopback(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(bindAddress.strip()).isLoopbackAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException("server.address '%s' cannot be resolved".formatted(bindAddress), e);
        }
    }

    private static String describe(String bindAddress) {
        return bindAddress == null || bindAddress.isBlank() ? "(all interfaces)" : bindAddress.strip();
    }
}
