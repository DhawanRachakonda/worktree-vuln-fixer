package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.runner.LaunchPlan.Flavor;
import dev.subscriber.session.SessionFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs the agent without a window and without a person ({@code copilot -p}). The generated script runs in a process of its own with
 * its output kept in {@code logs/runner.log}, and writes {@code exit.json} when the agent ends, so the states and records are the
 * same as for a window.
 */
@Component
public class HeadlessLauncher implements SessionLauncher {

    private final SubscriberProperties properties;
    private final SessionFolders folders;
    private final LauncherScripts scripts;
    private final Map<String, String> extraEnvironment;

    @Autowired
    public HeadlessLauncher(SubscriberProperties properties, SessionFolders folders, LauncherScripts scripts) {
        this(properties, folders, scripts, Map.of());
    }

    /** For tests: extra environment variables for the launched process, for example a PATH that finds a stand-in agent. */
    HeadlessLauncher(SubscriberProperties properties, SessionFolders folders, LauncherScripts scripts,
            Map<String, String> extraEnvironment) {
        this.properties = properties;
        this.folders = folders;
        this.scripts = scripts;
        this.extraEnvironment = extraEnvironment;
    }

    @Override
    public RunningSession launch(String sessionId, Path repository, String agent) throws IOException {
        boolean windows = isWindows();
        Path folder = folders.create(sessionId);
        LaunchPlan plan = new LaunchPlan(sessionId, folder, repository, agent, properties.agentCommand(),
                properties.copilot().permissionMode(), properties.copilot().allowedTools(),
                properties.copilot().deniedTools(), windows ? Flavor.HEADLESS_POWERSHELL : Flavor.HEADLESS_SHELL,
                properties.agentTool());
        Path script = scripts.write(plan);

        List<String> command = windows
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                : List.of("sh", script.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(folder.toFile())
                .redirectErrorStream(true)
                .redirectOutput(Files.createDirectories(folder.resolve("logs")).resolve("runner.log").toFile());
        extraEnvironment.forEach((key, value) -> setEnvironment(builder.environment(), key, value));
        return new ProcessSession(builder.start());
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Sets a variable, replacing an existing one whatever its letter case (Windows names are not case sensitive). */
    static void setEnvironment(Map<String, String> environment, String key, String value) {
        environment.keySet().stream().filter(k -> k.equalsIgnoreCase(key)).findFirst().ifPresent(environment::remove);
        environment.put(key, value);
    }
}
