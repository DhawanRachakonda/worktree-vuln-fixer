package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties.AgentTool;
import dev.subscriber.session.Templates;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Generates the script a session runs: it starts the agent, waits for it, and writes {@code exit.json} (exit code, end time, the
 * repository's commit) even when the agent is interrupted. Only validated values are substituted, and the repository path appears
 * only as a quoted literal, so no text from a message can run as a command.
 */
@Component
public class LauncherScripts {

    /** The script text for a plan. */
    public String render(LaunchPlan plan) {
        String prefix = plan.tool() == AgentTool.CLAUDE ? "claude-" : "";
        String template = template(prefix + switch (plan.flavor()) {
            case WINDOW_POWERSHELL -> "window.ps1";
            case HEADLESS_POWERSHELL -> "headless.ps1";
            case HEADLESS_SHELL -> "headless.sh";
            case REOPEN_POWERSHELL -> "reopen.ps1";
        });
        boolean powershell = plan.flavor() != LaunchPlan.Flavor.HEADLESS_SHELL;
        String sessionFolder = plan.sessionFolder().toString();
        String repository = plan.repository().toString();
        return Templates.fill(template, Map.of(
                "sessionFolder", powershell ? psQuoted(sessionFolder) : shQuoted(sessionFolder),
                "repository", powershell ? psQuoted(repository) : shQuoted(repository),
                "sessionId", plan.sessionId(),
                "title", plan.title(),
                "agent", plan.agent(),
                "command", plan.command(),
                "permissionFlags", permissionFlags(plan, powershell)));
    }

    /**
     * The permission arguments. A rule is placed in a single-quoted literal and the plan accepts no character that means anything
     * there. A headless session cannot ask anybody, so every mode but the allow-list gives it everything.
     */
    private static String permissionFlags(LaunchPlan plan, boolean powershell) {
        boolean headless = plan.flavor() == LaunchPlan.Flavor.HEADLESS_POWERSHELL || plan.flavor() == LaunchPlan.Flavor.HEADLESS_SHELL;
        if (plan.tool() == AgentTool.CLAUDE) {
            return claudePermissionFlags(plan, powershell, headless);
        }
        return switch (plan.permissionMode()) {
            case ALLOW_ALL_TOOLS -> " --allow-all-tools";
            case ASK -> headless ? " --allow-all-tools" : "";
            case ALLOW_LIST -> Stream.concat(
                    plan.allowedTools().stream().map(rule -> " --allow-tool " + quoted(rule, powershell)),
                    plan.deniedTools().stream().map(rule -> " --deny-tool " + quoted(rule, powershell))).collect(Collectors.joining());
        };
    }

    /**
     * Claude Code: the blanket permission is the permission mode {@code bypassPermissions}; the rules are one quoted, comma-joined argument for each of
     * {@code --allowedTools} and {@code --disallowedTools} (both are variadic, and the rules contain no comma and no quote, which the plan checked).
     */
    private static String claudePermissionFlags(LaunchPlan plan, boolean powershell, boolean headless) {
        return switch (plan.permissionMode()) {
            case ALLOW_ALL_TOOLS -> " --permission-mode bypassPermissions";
            case ASK -> headless ? " --permission-mode bypassPermissions" : "";
            case ALLOW_LIST -> " --allowedTools " + quoted(String.join(",", plan.allowedTools()), powershell)
                    + (plan.deniedTools().isEmpty() ? "" : " --disallowedTools " + quoted(String.join(",", plan.deniedTools()), powershell));
        };
    }

    private static String quoted(String rule, boolean powershell) {
        return "'" + (powershell ? psQuoted(rule) : shQuoted(rule)) + "'";
    }

    /**
     * Writes the script into the session folder and returns its path. PowerShell scripts get a UTF-8 byte order mark: Windows
     * PowerShell 5.1 reads a file without one as ANSI and would garble a non-ASCII folder name.
     */
    public Path write(LaunchPlan plan) throws IOException {
        Path file = plan.sessionFolder().resolve(plan.flavor().fileName());
        String text = render(plan);
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (plan.flavor() == LaunchPlan.Flavor.HEADLESS_SHELL) {
            Files.write(file, body);
        } else {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] all = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(body, 0, all, bom.length, body.length);
            Files.write(file, all);
        }
        return file;
    }

    /** A PowerShell single-quoted literal: nothing inside is expanded, and a quote is doubled. */
    static String psQuoted(String value) {
        return value.replace("'", "''");
    }

    /** The inside of a POSIX single-quoted string: a quote closes the string, adds an escaped quote, and reopens it. */
    static String shQuoted(String value) {
        return value.replace("'", "'\\''");
    }

    private static String template(String name) {
        try {
            return new String(new ClassPathResource("launcher/" + name).getContentAsByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the launcher template " + name, e);
        }
    }
}
