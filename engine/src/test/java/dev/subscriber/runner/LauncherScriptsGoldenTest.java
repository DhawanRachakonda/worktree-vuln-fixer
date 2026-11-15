package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties.PermissionMode;
import dev.subscriber.runner.LaunchPlan.Flavor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The generated scripts for one fixed plan with a hostile repository path, compared with reviewed files. Any change to a template or
 * to the quoting shows up here as a diff that a person has to read.
 */
class LauncherScriptsGoldenTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";
    private static final Path SESSION = Path.of("C:\\appmod data\\sessions\\" + ID);
    private static final Path HOSTILE = Path.of("C:\\work\\r&d 100% ^'x (1) é\\shop");

    private final LauncherScripts scripts = new LauncherScripts();

    private String render(Flavor flavor) {
        return scripts.render(new LaunchPlan(ID, SESSION, HOSTILE, "security-fixer", "copilot", PermissionMode.ALLOW_ALL_TOOLS, flavor));
    }

    private String renderAllowList(Flavor flavor) {
        return scripts.render(new LaunchPlan(ID, SESSION, HOSTILE, "security-fixer", "copilot", PermissionMode.ALLOW_LIST,
                List.of("shell(git:*)", "write(C:/work/**)", "shell(Test-Path)"), List.of("shell(curl:*)", "shell(git push:*)"), flavor));
    }

    @Test
    void allowListHeadlessShell() throws IOException {
        GoldenFiles.assertMatches(renderAllowList(Flavor.HEADLESS_SHELL), "headless-allow-list.sh");
    }

    @Test
    void allowListWindowPowerShell() throws IOException {
        GoldenFiles.assertMatches(renderAllowList(Flavor.WINDOW_POWERSHELL), "window-allow-list.ps1");
    }

    @Test
    void windowPowerShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.WINDOW_POWERSHELL), "window.ps1");
    }

    @Test
    void headlessPowerShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.HEADLESS_POWERSHELL), "headless.ps1");
    }

    @Test
    void headlessShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.HEADLESS_SHELL), "headless.sh");
    }

    @Test
    void reopenPowerShell() throws IOException {
        GoldenFiles.assertMatches(render(Flavor.REOPEN_POWERSHELL), "reopen.ps1");
    }
}
