package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties;
import dev.subscriber.runner.LaunchPlan.Flavor;
import dev.subscriber.session.SessionFolders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Opens the agent in a new console window (Windows) so a person can watch and type. The command is
 * {@code cmd.exe /c start "<title>" /wait powershell ... -File run.ps1}: {@code start} opens the window, {@code /wait} keeps
 * {@code cmd.exe} alive until it closes, and the script closes the window by ending. The process the engine holds is that
 * {@code cmd.exe}; its exit code says nothing about the agent (it was observed to be 0 whatever the script exited with), so the
 * result is always read from {@code exit.json}.
 *
 * <p>Nothing from a message is on this command line: only the window title, built from the start of the session id, and the path of
 * the generated script inside the session folder.
 */
@Component
public class WindowLauncher implements SessionLauncher {

    private final SubscriberProperties properties;
    private final SessionFolders folders;
    private final LauncherScripts scripts;
    private final Map<String, String> extraEnvironment;

    @Autowired
    public WindowLauncher(SubscriberProperties properties, SessionFolders folders, LauncherScripts scripts) {
        this(properties, folders, scripts, Map.of());
    }

    /** For tests: extra environment variables for the launched process, for example a PATH that finds a stand-in agent. */
    WindowLauncher(SubscriberProperties properties, SessionFolders folders, LauncherScripts scripts,
            Map<String, String> extraEnvironment) {
        this.properties = properties;
        this.folders = folders;
        this.scripts = scripts;
        this.extraEnvironment = extraEnvironment;
    }

    @Override
    public RunningSession launch(String sessionId, Path repository, String agent) throws IOException {
        if (!HeadlessLauncher.isWindows()) {
            throw new IOException("windows can only be opened on Windows; use the headless runner on this machine");
        }
        Path folder = folders.create(sessionId);
        LaunchPlan plan = new LaunchPlan(sessionId, folder, repository, agent, properties.agentCommand(),
                properties.copilot().permissionMode(), properties.copilot().allowedTools(),
                properties.copilot().deniedTools(), Flavor.WINDOW_POWERSHELL, properties.agentTool());
        Path script = scripts.write(plan);

        // The title and the script path contain spaces at times; ProcessBuilder quotes an argument that has one.
        List<String> command = List.of("cmd.exe", "/c", "start", plan.title(), "/wait", "powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-File", script.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(folder.toFile())
                .redirectErrorStream(true)
                .redirectOutput(Files.createDirectories(folder.resolve("logs")).resolve("runner.log").toFile());
        extraEnvironment.forEach((key, value) -> HeadlessLauncher.setEnvironment(builder.environment(), key, value));
        return new ProcessSession(builder.start());
    }
}
