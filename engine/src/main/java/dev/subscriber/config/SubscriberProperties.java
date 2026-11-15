package dev.subscriber.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All settings of the engine. Each record validates itself, so an invalid value stops the start with a message that names the
 * setting (as {@code subscriber.<kebab-case-name>}).
 *
 * @param dataDir      folder of the H2 file database and the session folders; restricted to a safe character set because
 *                     it appears in generated launch commands
 * @param allowedRoots folders that reports and repositories must lie inside (required)
 * @param subjects     the issue subjects, each with its agent and how its repository is found
 * @param runner       window (Windows), headless, or auto
 * @param maxParallel  sessions that may run at once (never two in one repository)
 * @param agentTool    the coding agent that runs a session: the GitHub Copilot CLI or Claude Code
 * @param claude       how Claude Code is started (used when the tool is claude)
 */
@ConfigurationProperties("subscriber")
public record SubscriberProperties(
        @DefaultValue("./data") String dataDir,
        List<String> allowedRoots,
        List<Subject> subjects,
        @DefaultValue("auto") Runner runner,
        @DefaultValue("1") int maxParallel,
        @DefaultValue Copilot copilot,
        @DefaultValue Nats nats,
        @DefaultValue Access access,
        @DefaultValue("worktree") Isolation isolation,
        @DefaultValue("copilot") AgentTool agentTool,
        @DefaultValue Claude claude) {

    private static final Pattern DATA_DIR = Pattern.compile("[A-Za-z0-9 ._:~\\\\/-]+");
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*");
    private static final Pattern AGENT = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9 _.-]{1,60}");
    private static final Pattern COMMAND = Pattern.compile("[A-Za-z0-9_.-]+");

    public enum Runner { AUTO, WINDOW, HEADLESS }

    /** The runner actually used: {@code auto} means a window on Windows and headless elsewhere. */
    public Runner effectiveRunner() {
        return effectiveRunner(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    Runner effectiveRunner(boolean windows) {
        return runner == Runner.AUTO ? (windows ? Runner.WINDOW : Runner.HEADLESS) : runner;
    }

    /** The coding agent a session runs. */
    public enum AgentTool { COPILOT, CLAUDE }

    /** What the agent may do without asking: everything, nothing (the person approves in the window), or what a list of rules allows. */
    public enum PermissionMode { ALLOW_ALL_TOOLS, ASK, ALLOW_LIST }

    /** Where a fix session works: in its own git worktree, or in the repository's own checkout. */
    public enum Isolation { WORKTREE, NONE }

    /** Settings without isolation: sessions work in the repository's own checkout (what the engine did before worktrees). */
    public SubscriberProperties(String dataDir, List<String> allowedRoots, List<Subject> subjects, Runner runner, int maxParallel,
            Copilot copilot, Nats nats, Access access) {
        this(dataDir, allowedRoots, subjects, runner, maxParallel, copilot, nats, access, Isolation.NONE);
    }

    /** Settings that do not choose a tool: the GitHub Copilot CLI, as before there was a choice. */
    public SubscriberProperties(String dataDir, List<String> allowedRoots, List<Subject> subjects, Runner runner, int maxParallel,
            Copilot copilot, Nats nats, Access access, Isolation isolation) {
        this(dataDir, allowedRoots, subjects, runner, maxParallel, copilot, nats, access, isolation, AgentTool.COPILOT, new Claude("claude"));
    }

    @ConstructorBinding
    public SubscriberProperties {
        if (dataDir == null || !DATA_DIR.matcher(dataDir).matches()) {
            throw new IllegalArgumentException("""
                    subscriber.data-dir may only contain letters, digits, spaces and . _ : ~ \\ / - \
                    (it is used in generated launch commands), but is: %s""".formatted(dataDir));
        }
        allowedRoots = allowedRoots == null ? List.of() : allowedRoots.stream().filter(r -> r != null && !r.isBlank())
                .map(String::strip).toList();
        if (allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("""
                    subscriber.allowed-roots is required: name at least one existing folder \
                    that reports and repositories must lie inside (environment: SUBSCRIBER_ALLOWED_ROOTS, comma separated)""");
        }
        for (String root : allowedRoots) {
            Path p = Path.of(root);
            if (!p.isAbsolute() || !Files.isDirectory(p)) {
                throw new IllegalArgumentException("subscriber.allowed-roots entry is not an existing absolute folder: " + root);
            }
        }
        isolation = isolation == null ? Isolation.WORKTREE : isolation;
        agentTool = agentTool == null ? AgentTool.COPILOT : agentTool;
        claude = claude == null ? new Claude("claude") : claude;
        // A subject that does not say takes the general setting, so every subject has its own answer from here on.
        Isolation general = isolation;
        subjects = subjects == null ? List.of() : subjects.stream()
                .map(s -> s.isolation() == null ? s.withIsolation(general) : s).toList();
        Set<String> seen = new HashSet<>();
        for (Subject s : subjects) {
            if (!seen.add(s.subject())) {
                throw new IllegalArgumentException("subscriber.subjects lists the subject more than once: " + s.subject());
            }
        }
        if (maxParallel < 1 || maxParallel > 16) {
            throw new IllegalArgumentException("subscriber.max-parallel must be between 1 and 16, but is " + maxParallel);
        }
    }

    /** One issue subject: which agent works on it and where its repository comes from. */
    public record Subject(String subject, String source, @DefaultValue("appmod-harness") String agent, String repository,
            String reportLocation, Isolation isolation) {

        /** A subject that takes the general isolation setting. */
        public Subject(String subject, String source, String agent, String repository, String reportLocation) {
            this(subject, source, agent, repository, reportLocation, null);
        }

        Subject withIsolation(Isolation value) {
            return new Subject(subject, source, agent, repository, reportLocation, value);
        }

        @ConstructorBinding
        public Subject {
            if (subject == null || !SUBJECT.matcher(subject).matches()) {
                throw new IllegalArgumentException("""
                        subscriber.subjects[].subject must be dot-separated tokens without \
                        wildcards or spaces, but is: %s""".formatted(subject));
            }
            if (source == null || !LABEL.matcher(source).matches()) {
                throw new IllegalArgumentException("""
                        subscriber.subjects[].source of %s must be 1 to 60 letters, digits, spaces or _ . -, but is: %s"""
                        .formatted(subject, source));
            }
            if (agent == null || !AGENT.matcher(agent).matches()) {
                throw new IllegalArgumentException("""
                        subscriber.subjects[].agent of %s may only contain letters, digits and _ . : -, but is: %s"""
                        .formatted(subject, agent));
            }
            repository = blankToNull(repository);
            reportLocation = blankToNull(reportLocation);
            if (repository != null && reportLocation != null) {
                throw new IllegalArgumentException("""
                        subscriber.subjects[] of %s sets both repository and report-location; use one: a fixed repository, \
                        or a report location from which the repository is derived""".formatted(subject));
            }
            if (repository != null && !Path.of(repository).isAbsolute()) {
                throw new IllegalArgumentException("""
                        subscriber.subjects[].repository of %s must be an absolute path, but is: %s"""
                        .formatted(subject, repository));
            }
            if (reportLocation != null) {
                String normalized = reportLocation.replace('\\', '/');
                if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")
                        || List.of(normalized.split("/")).contains("..") || normalized.endsWith("/")) {
                    throw new IllegalArgumentException("""
                            subscriber.subjects[].report-location of %s must be a relative file path without '..', but is: %s"""
                            .formatted(subject, reportLocation));
                }
                reportLocation = normalized;
            }
        }
    }

    /** The command that starts Claude Code, resolved through the PATH. */
    public record Claude(@DefaultValue("claude") String command) {

        @ConstructorBinding
        public Claude {
            if (command == null || !COMMAND.matcher(command).matches()) {
                throw new IllegalArgumentException("""
                        subscriber.claude.command must be a command name (letters, digits and _ . -), but is: %s"""
                        .formatted(command));
            }
        }
    }

    /** The command of the selected tool. */
    public String agentCommand() {
        return agentTool == AgentTool.CLAUDE ? claude.command() : copilot.command();
    }

    /**
     * How the agent is started. The permission settings apply to whichever tool runs; the rules are written in that tool's own syntax.
     *
     * @param allowedTools tool rules the agent may use without asking, as the Copilot CLI writes them (for example {@code shell(git:*)});
     *                     only with the allow-list mode
     * @param deniedTools  tool rules the agent may never use; deny rules win over every allow rule; only with the allow-list mode
     */
    public record Copilot(@DefaultValue("copilot") String command,
            @DefaultValue("allow-all-tools") PermissionMode permissionMode, List<String> allowedTools, List<String> deniedTools) {

        /** A rule is placed in a single-quoted literal of a generated script, so only characters with no meaning there are accepted. */
        private static final Pattern RULE = Pattern.compile("[A-Za-z0-9 _.:*?/\\\\()~-]{1,200}");

        /** A mode without rules: the short form used where no allow-list is involved. */
        public Copilot(String command, PermissionMode permissionMode) {
            this(command, permissionMode, List.of(), List.of());
        }

        @ConstructorBinding
        public Copilot {
            if (command == null || !COMMAND.matcher(command).matches()) {
                throw new IllegalArgumentException("""
                        subscriber.copilot.command must be a command name (letters, digits and _ . -), but is: %s"""
                        .formatted(command));
            }
            permissionMode = permissionMode == null ? PermissionMode.ALLOW_ALL_TOOLS : permissionMode;
            allowedTools = rules(allowedTools, "allowed-tools");
            deniedTools = rules(deniedTools, "denied-tools");
            if (permissionMode == PermissionMode.ALLOW_LIST && allowedTools.isEmpty()) {
                throw new IllegalArgumentException("""
                        subscriber.copilot.allowed-tools is required when subscriber.copilot.permission-mode is allow-list: name at \
                        least one rule, for example shell(git:*) (environment: SUBSCRIBER_COPILOT_ALLOWED_TOOLS, comma separated)""");
            }
            if (permissionMode != PermissionMode.ALLOW_LIST && !(allowedTools.isEmpty() && deniedTools.isEmpty())) {
                throw new IllegalArgumentException("""
                        subscriber.copilot.allowed-tools and denied-tools are only used with subscriber.copilot.permission-mode \
                        allow-list, but the mode is %s: select allow-list, or remove the rules so that nobody expects a \
                        restriction that does not apply""".formatted(permissionMode.name().toLowerCase(Locale.ROOT).replace('_', '-')));
            }
        }

        private static List<String> rules(List<String> rules, String setting) {
            if (rules == null) {
                return List.of();
            }
            List<String> kept = rules.stream().filter(r -> r != null && !r.isBlank()).map(String::strip).toList();
            for (int i = 0; i < kept.size(); i++) {
                if (!RULE.matcher(kept.get(i)).matches()) {
                    // The rule is not echoed: it is the text that must never reach a command line.
                    throw new IllegalArgumentException("""
                            subscriber.copilot.%s[%d] is not a plain tool rule: it may only contain letters, digits, spaces and \
                            _ . : * ? / \\ ( ) ~ - and be 1 to 200 characters long""".formatted(setting, i));
                }
            }
            return kept;
        }
    }

    /** NATS connection; secrets are never printed. */
    public record Nats(@DefaultValue("nats://localhost:4222") String url, String token, String user, String password,
            String credentialsFile) {

        public Nats {
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("nats://") || lower.startsWith("tls://"))) {
                throw new IllegalArgumentException("subscriber.nats.url must start with nats:// or tls://, but is: " + url);
            }
            if (url.contains("@")) {
                throw new IllegalArgumentException("""
                        subscriber.nats.url must not contain credentials (user:password@host): use \
                        token, user and password, or credentials-file, which are never logged""");
            }
            token = blankToNull(token);
            user = blankToNull(user);
            password = blankToNull(password);
            credentialsFile = blankToNull(credentialsFile);
            int methods = (token != null ? 1 : 0) + (user != null ? 1 : 0) + (credentialsFile != null ? 1 : 0);
            if (methods > 1) {
                throw new IllegalArgumentException(
                        "subscriber.nats: use only one of token, user and password, or credentials-file");
            }
            if ((user == null) != (password == null)) {
                throw new IllegalArgumentException("subscriber.nats.user and subscriber.nats.password must be set together");
            }
            if (credentialsFile != null && !Files.isRegularFile(Path.of(credentialsFile))) {
                throw new IllegalArgumentException("subscriber.nats.credentials-file does not exist");
            }
        }

        @Override
        public String toString() {
            String authentication = token != null ? "token" : user != null ? "user" : credentialsFile != null ? "credentials-file" : "none";
            return "Nats[url=%s, authentication=%s]".formatted(url, authentication);
        }
    }

    /** Access to the read-only API when it is not bound to loopback. */
    public record Access(String token) {

        public Access {
            token = blankToNull(token);
            if (token != null && token.length() < 16) {
                throw new IllegalArgumentException("subscriber.access.token must be at least 16 characters");
            }
        }

        @Override
        public String toString() {
            return "Access[token=%s]".formatted(token == null ? "none" : "set");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
