package dev.subscriber.console;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.shell.core.ShellRunner;

/**
 * Takes over how Spring Shell is started, for two reasons.
 *
 * <p>Spring Shell's own application runner hands every command-line argument to the shell as a command, so a start such as
 * {@code java -jar engine.jar --server.port=9090} would fail with "command not found"; and it blocks the main thread while the
 * prompt is open, before Spring Boot publishes {@code ApplicationReadyEvent}, which is when the engine recovers, starts
 * dispatching and connects to NATS. With the console on, none of that would happen until the person typed {@code exit}.
 *
 * <p>So the runner bean Spring Shell looks for (it creates its own only when no bean has this name) does nothing, and the shell is
 * started after the application is ready, on its own thread, without arguments. When the shell ends, the engine stops.
 */
@Configuration(proxyBeanMethods = false)
public class ConsoleRunnerConfig {

    @Bean("springShellApplicationRunner")
    ApplicationRunner springShellApplicationRunner() {
        return arguments -> {
        };
    }

    @Bean
    ConsoleStarter consoleStarter(ObjectProvider<ShellRunner> shell, Environment environment) {
        return new ConsoleStarter(shell, environment.getProperty("spring.shell.interactive.enabled", Boolean.class, false));
    }

    /** Starts the shell once the application is ready. */
    static final class ConsoleStarter {

        private final ObjectProvider<ShellRunner> shell;
        private final boolean enabled;

        ConsoleStarter(ObjectProvider<ShellRunner> shell, boolean enabled) {
            this.shell = shell;
            this.enabled = enabled;
        }

        @EventListener
        void onReady(ApplicationReadyEvent event) {
            if (enabled) {
                start(shell.getObject(), event.getApplicationContext()::close);
            }
        }

        /** Runs the shell on its own thread and calls {@code whenEnded} after it returns, however it ends. */
        Thread start(ShellRunner runner, Runnable whenEnded) {
            return Thread.ofPlatform().name("operator-console").start(() -> {
                try {
                    runner.run(new String[0]);
                } catch (Exception e) {
                    // The console is a convenience: when it fails the engine still has to stop cleanly, not hang without a prompt.
                    System.err.println("The operator console stopped: %s".formatted(Text.printable(e.toString(), 200)));
                } finally {
                    whenEnded.run();
                }
            });
        }
    }
}
