package dev.subscriber.console;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Decides before the application starts whether the operator console (Spring Shell) runs, from {@code subscriber.console.enabled}:
 * {@code auto} (the default) turns it on only when standard input and output are a terminal, {@code true} and {@code false} force it.
 *
 * <p>With the console on, log output moves from the terminal to {@code <data dir>/engine.log} so log lines do not tear the prompt.
 * With it off, Spring Shell's interactive runner stays off, so the engine starts exactly as without a terminal.
 */
public class ConsoleModeProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SETTING = "subscriber.console.enabled";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        apply(environment, hasTerminal());
    }

    void apply(ConfigurableEnvironment environment, boolean terminal) {
        boolean on = resolve(environment.getProperty(SETTING), terminal);
        Map<String, Object> source = new java.util.HashMap<>();
        source.put("spring.shell.interactive.enabled", on);
        source.put("spring.shell.history.enabled", false);
        if (on) {
            String dataDir = environment.getProperty("subscriber.data-dir", "./data");
            source.put("logging.console.enabled", false);
            source.put("logging.file.name", logFile(dataDir).toString());
        }
        environment.getPropertySources().addFirst(new MapPropertySource("subscriberConsoleMode", source));
    }

    static boolean resolve(String setting, boolean terminal) {
        String value = setting == null || setting.isBlank() ? "auto" : setting.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "auto" -> terminal;
            case "true" -> {
                if (!terminal) {
                    throw new IllegalStateException("""
                            %s=true needs a terminal: standard input and output are redirected; \
                            start the engine from a terminal, or use auto or false""".formatted(SETTING));
                }
                yield true;
            }
            case "false" -> false;
            default -> throw new IllegalStateException("%s must be auto, true or false, not '%s'".formatted(SETTING, printable(setting)));
        };
    }

    static Path logFile(String dataDir) {
        return Path.of(dataDir).toAbsolutePath().normalize().resolve("engine.log");
    }

    private static boolean hasTerminal() {
        java.io.Console console = System.console();
        return console != null && console.isTerminal();
    }

    private static String printable(String text) {
        String clean = text.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > 40 ? clean.substring(0, 40) + "..." : clean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
