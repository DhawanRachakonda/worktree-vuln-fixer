package dev.subscriber.config;

import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.config.SubscriberProperties.Runner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Stops the start of an unattended engine whose agent would run with a blanket permission that nobody chose. Headless, nobody can
 * approve a tool, so {@code ask} cannot be honoured, and the default {@code allow-all-tools} was not a decision.
 */
@Component
public class HeadlessPermissionGuard {

    private static final Logger log = LoggerFactory.getLogger(HeadlessPermissionGuard.class);

    private static final String SETTING = "subscriber.copilot.permission-mode";

    public HeadlessPermissionGuard(SubscriberProperties properties, Environment environment) {
        String set = environment.getProperty(SETTING);
        check(properties.effectiveRunner(), properties.copilot().permissionMode(), set != null && !set.isBlank());
    }

    /** @param explicit whether the permission mode was set by the operator and not taken from the default */
    static void check(Runner effectiveRunner, PermissionMode mode, boolean explicit) {
        if (effectiveRunner != Runner.HEADLESS || mode == PermissionMode.ALLOW_LIST) {
            return;
        }
        if (mode == PermissionMode.ASK) {
            throw new IllegalStateException("""
                    subscriber.copilot.permission-mode is ask, but the runner is headless and nobody can answer: select allow-list \
                    (with subscriber.copilot.allowed-tools), or allow-all-tools if the agent may use every tool unattended \
                    (environment: SUBSCRIBER_COPILOT_PERMISSION_MODE)""");
        }
        if (!explicit) {
            throw new IllegalStateException("""
                    The runner is headless and subscriber.copilot.permission-mode is not set, which would run the agent with every \
                    tool allowed and nobody to approve: select allow-list (with subscriber.copilot.allowed-tools), or set \
                    allow-all-tools on purpose (environment: SUBSCRIBER_COPILOT_PERMISSION_MODE)""");
        }
        log.warn("The runner is headless and the permission mode is allow-all-tools: the agent runs with every tool allowed and nobody to approve.");
    }
}
