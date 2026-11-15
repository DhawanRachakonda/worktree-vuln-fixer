package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties.AgentTool;
import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.runner.LaunchPlan.Flavor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Claude Code scripts for one fixed plan with a hostile repository path, compared with reviewed files. */
class ClaudeLauncherScriptsGoldenTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";
    private static final Path SESSION = Path.of("C:\\appmod data\\sessions\\" + ID);
    private static final Path HOSTILE = Path.of("C:\\work\\r&d 100% ^'x (1) é\\shop");

    private final LauncherScripts scripts = new LauncherScripts();

    private String render(Flavor flavor) {
        return scripts.render(new LaunchPlan(ID, SESSION, HOSTILE, "security-fixer", "claude", PermissionMode.ALLOW_ALL_TOOLS,
                List.of(), List.of(), flavor, AgentTool.CLAUDE));
    }

    private String renderAllowList(Flavor flavor) {
        return scripts.render(new LaunchPlan(ID, SESSION, HOSTILE, "security-fixer", "claude", PermissionMode.ALLOW_LIST,
                List.of("Read", "Edit", "Bash(git *)", "Bash(node *)"), List.of("Bash(curl *)", "Bash(git push *)"), flavor, AgentTool.CLAUDE));
    }

    @Test
    void windowPowerShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.WINDOW_POWERSHELL), "claude-window.ps1");
    }

    @Test
    void headlessPowerShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.HEADLESS_POWERSHELL), "claude-headless.ps1");
    }

    @Test
    void headlessShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.HEADLESS_SHELL), "claude-headless.sh");
    }

    @Test
    void reopenPowerShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.REOPEN_POWERSHELL), "claude-reopen.ps1");
    }

    @Test
    void allowListHeadlessShell() throws IOException {
        GoldenFiles.assertMatches(renderAllowList(Flavor.HEADLESS_SHELL), "claude-headless-allow-list.sh");
    }

    @Test
    void allowListWindowPowerShell() throws IOException {
        GoldenFiles.assertMatches(renderAllowList(Flavor.WINDOW_POWERSHELL), "claude-window-allow-list.ps1");
    }
}
