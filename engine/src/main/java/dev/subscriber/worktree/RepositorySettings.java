package dev.subscriber.worktree;

import dev.subscriber.config.SubscriberProperties.PublishMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings of one repository, read from {@code .appmod/publish.json} inside it: the saved source branch, the publish mode and
 * the pull-request command. Every key is optional; a missing key means "the engine's default". The file is strict: an unknown key,
 * a value of the wrong type or an invalid branch name makes the whole file a problem, so a typo never turns into a silent default.
 *
 * @param sourceBranch       the branch fixes are based on, or null
 * @param mode               the publish mode, or null for the engine's
 * @param pullRequestCommand the command template, or null for the engine's
 */
public record RepositorySettings(String sourceBranch, PublishMode mode, String pullRequestCommand) {

    public static final String LOCATION = ".appmod/publish.json";
    public static final RepositorySettings EMPTY = new RepositorySettings(null, null, null);

    private static final Set<String> KEYS = Set.of("sourceBranch", "mode", "pullRequestCommand");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** What reading found: the settings (empty when there is no file) or why the file cannot be used. */
    public record Read(RepositorySettings settings, String problem) {

        public boolean ok() {
            return problem == null;
        }
    }

    public static Read read(Path repository) {
        Path file = repository.resolve(LOCATION);
        if (!Files.exists(file)) {
            return new Read(EMPTY, null);
        }
        if (!Files.isRegularFile(file)) {
            return new Read(EMPTY, "%s is not a file".formatted(LOCATION));
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readString(file));
        } catch (IOException | RuntimeException _) {
            return new Read(EMPTY, "%s is not valid JSON".formatted(LOCATION));
        }
        if (root == null || !root.isObject()) {
            return new Read(EMPTY, "%s must hold a JSON object".formatted(LOCATION));
        }
        for (String key : root.propertyNames()) {
            if (!KEYS.contains(key)) {
                return new Read(EMPTY, "%s has the unknown key \"%s\" (known: sourceBranch, mode, pullRequestCommand)".formatted(LOCATION, key));
            }
        }
        String branch = text(root, "sourceBranch");
        String mode = text(root, "mode");
        String command = text(root, "pullRequestCommand");
        if (branch == NOT_TEXT || mode == NOT_TEXT || command == NOT_TEXT) {
            return new Read(EMPTY, "%s: sourceBranch, mode and pullRequestCommand must be text".formatted(LOCATION));
        }
        if (branch != null) {
            String why = BranchNames.problem(branch);
            if (why != null) {
                return new Read(EMPTY, "%s: sourceBranch \"%s\" is not usable: %s".formatted(LOCATION, printable(branch), why));
            }
        }
        PublishMode parsed = null;
        if (mode != null) {
            parsed = switch (mode.toLowerCase(Locale.ROOT)) {
                case "none" -> PublishMode.NONE;
                case "push" -> PublishMode.PUSH;
                case "pull-request" -> PublishMode.PULL_REQUEST;
                default -> null;
            };
            if (parsed == null) {
                return new Read(EMPTY, "%s: mode must be none, push or pull-request".formatted(LOCATION));
            }
        }
        if (command != null && (command.length() > 500 || command.chars().anyMatch(c -> c < 32))) {
            return new Read(EMPTY, "%s: pullRequestCommand must be one line of at most 500 characters".formatted(LOCATION));
        }
        return new Read(new RepositorySettings(branch, parsed, command), null);
    }

    private static final String NOT_TEXT = new String("not text");

    private static String text(JsonNode root, String key) {
        JsonNode value = root.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            return NOT_TEXT;
        }
        String text = value.asString().strip();
        return text.isEmpty() ? null : text;
    }

    private static String printable(String text) {
        String cleaned = text.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) + "..." : cleaned;
    }
}
