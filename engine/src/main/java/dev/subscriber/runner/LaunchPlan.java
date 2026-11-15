package dev.subscriber.runner;

import dev.subscriber.config.SubscriberProperties.AgentTool;
import dev.subscriber.config.SubscriberProperties.PermissionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything a launcher script is generated from. The constructor accepts only values that are safe to place in a script: a UUID, an
 * agent name and a command name made of plain characters, and paths without control characters. The repository path may contain any
 * other character (spaces, {@code &}, {@code %}, {@code '}), because it only ever appears inside a quoted literal.
 *
 * @param sessionFolder the session's folder (built by the engine from the data directory and the id)
 * @param repository    the repository the agent works in
 * @param command       the command name of the tool that runs (Copilot or Claude Code), resolved through the PATH
 * @param allowedTools  tool rules passed as {@code --allow-tool}; only used with the allow-list mode
 * @param deniedTools   tool rules passed as {@code --deny-tool}; only used with the allow-list mode
 * @param tool          the coding agent the script starts: the Copilot CLI or Claude Code
 */
public record LaunchPlan(
        String sessionId,
        Path sessionFolder,
        Path repository,
        String agent,
        String command,
        PermissionMode permissionMode,
        List<String> allowedTools,
        List<String> deniedTools,
        Flavor flavor,
        AgentTool tool) {

    /** A plan for the Copilot CLI, which is what every plan was before there was a choice of tool. */
    public LaunchPlan(String sessionId, Path sessionFolder, Path repository, String agent, String command,
            PermissionMode permissionMode, List<String> allowedTools, List<String> deniedTools, Flavor flavor) {
        this(sessionId, sessionFolder, repository, agent, command, permissionMode, allowedTools, deniedTools, flavor, AgentTool.COPILOT);
    }

    /** A plan without tool rules. */
    public LaunchPlan(String sessionId, Path sessionFolder, Path repository, String agent, String command,
            PermissionMode permissionMode, Flavor flavor) {
        this(sessionId, sessionFolder, repository, agent, command, permissionMode, List.of(), List.of(), flavor);
    }

    /** Which script is generated. */
    public enum Flavor {
        /** PowerShell script run in its own console window: interactive. */
        WINDOW_POWERSHELL("run.ps1"),
        /** PowerShell script run without a window: non-interactive. */
        HEADLESS_POWERSHELL("run.ps1"),
        /** POSIX shell script run without a window: non-interactive. */
        HEADLESS_SHELL("run.sh"),
        /** PowerShell script run in its own window to resume a finished session; it records nothing. */
        REOPEN_POWERSHELL("reopen.ps1");

        private final String fileName;

        Flavor(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    private static final Pattern UUID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern AGENT = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern COMMAND = Pattern.compile("[A-Za-z0-9_.-]+");
    /** The same character set the settings accept: a rule is placed inside a single-quoted literal. */
    private static final Pattern RULE = Pattern.compile("[A-Za-z0-9 _.:*?/\\\\()~-]{1,200}");

    public LaunchPlan {
        require(sessionId != null && UUID.matcher(sessionId).matches(), "session id is not a UUID");
        require(agent != null && AGENT.matcher(agent).matches(), "agent name contains characters that are not allowed");
        require(command != null && COMMAND.matcher(command).matches(), "command name contains characters that are not allowed");
        require(permissionMode != null && flavor != null && tool != null, "permission mode, flavor and tool are required");
        allowedTools = rules(allowedTools, "allowed tool");
        deniedTools = rules(deniedTools, "denied tool");
        require(permissionMode == PermissionMode.ALLOW_LIST || (allowedTools.isEmpty() && deniedTools.isEmpty()),
                "tool rules are only used with the allow-list mode");
        require(permissionMode != PermissionMode.ALLOW_LIST || !allowedTools.isEmpty(), "the allow-list mode needs an allow rule");
        requirePlainPath(sessionFolder, "session folder");
        requirePlainPath(repository, "repository");
        require(!sessionFolder.toString().contains("'") && !sessionFolder.toString().contains("\""),
                "session folder contains a quote");
    }

    /** The title of the window and the name of the agent session: fixed text plus the start of the id. */
    public String title() {
        return "appmod fix " + sessionId.substring(0, 8);
    }

    private static List<String> rules(List<String> rules, String what) {
        List<String> kept = rules == null ? List.of() : List.copyOf(rules);
        kept.forEach(rule -> require(RULE.matcher(rule).matches(), "a %s rule contains characters that are not allowed".formatted(what)));
        return kept;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("cannot build a launch plan: " + message);
        }
    }

    private static void requirePlainPath(Path path, String what) {
        require(path != null, "%s is required".formatted(what));
        require(path.toString().chars().noneMatch(c -> c < 0x20 || c == 0x7f), "%s contains a control character".formatted(what));
    }
}
