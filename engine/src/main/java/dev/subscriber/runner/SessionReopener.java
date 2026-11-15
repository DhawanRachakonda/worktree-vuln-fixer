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
 * Opens a window that resumes the agent's conversation of a finished session ({@code copilot --resume <session id>}) in the
 * session's repository, through the same safe script generation as a first start: the command line holds only the window title
 * and the path of a generated script, and the repository path appears only as a quoted literal inside it.
 *
 * <p>The window is the person's: the engine does not wait for it and records nothing about it, so the session's state and exit
 * record stay as they were.
 */
@Component
public class SessionReopener {

    private final SubscriberProperties properties;
    private final SessionFolders folders;
    private final LauncherScripts scripts;
    private final Map<String, String> extraEnvironment;

    @Autowired
    public SessionReopener(SubscriberProperties properties, SessionFolders folders, LauncherScripts scripts) {
        this(properties, folders, scripts, Map.of());
    }

    /** For tests: extra environment variables for the launched process, for example a PATH that finds a stand-in agent. */
    SessionReopener(SubscriberProperties properties, SessionFolders folders, LauncherScripts scripts,
            Map<String, String> extraEnvironment) {
        this.properties = properties;
        this.folders = folders;
        this.scripts = scripts;
        this.extraEnvironment = extraEnvironment;
    }

    /**
     * Opens the window; returns when {@code cmd.exe} has handed it over, not when the person closes it. The conversation lives in the store of the tool that ran
     * the session, so that tool resumes it, whatever the engine is configured with now.
     *
     * @param engine the tool recorded for the session ({@code copilot} or {@code claude}); anything else means the configured one
     */
    public void reopen(String sessionId, Path repository, String engine) throws IOException {
        if (!HeadlessLauncher.isWindows()) {
            throw new IOException("a window can only be opened on Windows");
        }
        Path folder = folders.folder(sessionId);
        if (!Files.isDirectory(folder)) {
            throw new IOException("the session folder no longer exists");
        }
        SubscriberProperties.AgentTool tool = "claude".equals(engine) ? SubscriberProperties.AgentTool.CLAUDE
                : "copilot".equals(engine) ? SubscriberProperties.AgentTool.COPILOT : properties.agentTool();
        String toolCommand = tool == SubscriberProperties.AgentTool.CLAUDE ? properties.claude().command() : properties.copilot().command();
        LaunchPlan plan = new LaunchPlan(sessionId, folder, repository, "reopen", toolCommand,
                properties.copilot().permissionMode(), properties.copilot().allowedTools(),
                properties.copilot().deniedTools(), Flavor.REOPEN_POWERSHELL, tool);
        Path script = scripts.write(plan);

        List<String> command = List.of("cmd.exe", "/c", "start", "%s (reopened)".formatted(plan.title()), "powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-File", script.toString());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(folder.toFile())
                .redirectErrorStream(true)
                .redirectOutput(Files.createDirectories(folder.resolve("logs")).resolve("reopen.log").toFile());
        extraEnvironment.forEach((key, value) -> HeadlessLauncher.setEnvironment(builder.environment(), key, value));
        builder.start();
    }
}
