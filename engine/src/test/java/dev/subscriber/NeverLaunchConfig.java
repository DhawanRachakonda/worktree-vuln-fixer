package dev.subscriber;

import dev.subscriber.runner.SessionLauncher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * For application tests that must never start an agent: replaces the launcher selected by the engine with one that fails loudly if it
 * is ever used. Needs {@code spring.main.allow-bean-definition-overriding=true}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class NeverLaunchConfig {

    @Bean("sessionLauncher")
    @Primary
    SessionLauncher neverLaunch() {
        return (sessionId, repository, agent) -> {
            throw new AssertionError("a test started a session for real: " + sessionId);
        };
    }
}
