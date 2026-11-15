package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.config.SubscriberProperties.Runner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Chooses the launcher the engine uses, from {@code subscriber.runner}. */
@Configuration(proxyBeanMethods = false)
public class LauncherConfiguration {

    @Bean
    @Primary
    SessionLauncher sessionLauncher(SubscriberProperties properties, HeadlessLauncher headless, WindowLauncher window) {
        return select(properties.effectiveRunner(), HeadlessLauncher.isWindows(), headless, window);
    }

    /**
     * {@code auto} was already resolved to a window on Windows and headless elsewhere. Asking for a window on a platform that cannot
     * open one is a configuration error that stops the start, not something to quietly replace by headless.
     */
    static SessionLauncher select(Runner effective, boolean windows, SessionLauncher headless, SessionLauncher window) {
        if (effective == Runner.WINDOW) {
            if (!windows) {
                throw new IllegalStateException("subscriber.runner=window is only supported on Windows; use headless or auto here");
            }
            return window;
        }
        return headless;
    }
}
