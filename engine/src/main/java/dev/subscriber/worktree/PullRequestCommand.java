package dev.subscriber.worktree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The operator's pull-request command template, for example {@code gh pr create --base {base} --head {head} --title {title}
 * --body-file {bodyFile}}. The template is split into arguments first (single or double quotes group words) and the placeholders are
 * filled afterwards, one argument at a time, so a value can never turn into a second argument or into shell syntax: the command runs
 * without a shell. Only four placeholders exist, and all four are filled with values the engine made.
 */
final class PullRequestCommand {

    static final Set<String> PLACEHOLDERS = Set.of("base", "head", "title", "bodyFile");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z]*)\\}");

    /** The arguments (program first), or why the template cannot be used. */
    record Parsed(List<String> arguments, String problem) {

        boolean ok() {
            return problem == null;
        }

        String program() {
            return arguments.getFirst();
        }
    }

    private PullRequestCommand() {
    }

    static Parsed parse(String template) {
        if (template == null || template.isBlank()) {
            return new Parsed(List.of(), "the pull-request command is empty");
        }
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (char c : template.strip().toCharArray()) {
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inWord = false;
                }
            } else {
                current.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            return new Parsed(List.of(), "the pull-request command has a quote that is not closed");
        }
        if (inWord) {
            arguments.add(current.toString());
        }
        if (arguments.isEmpty() || arguments.getFirst().isEmpty()) {
            return new Parsed(List.of(), "the pull-request command names no program");
        }
        if (PLACEHOLDER.matcher(arguments.getFirst()).find()) {
            return new Parsed(List.of(), "the program of the pull-request command cannot be a placeholder");
        }
        for (String argument : arguments) {
            Matcher found = PLACEHOLDER.matcher(argument);
            while (found.find()) {
                if (!PLACEHOLDERS.contains(found.group(1))) {
                    return new Parsed(List.of(), "the pull-request command has the unknown placeholder {%s} (known: {base}, {head}, {title}, {bodyFile})"
                            .formatted(found.group(1)));
                }
            }
        }
        return new Parsed(List.copyOf(arguments), null);
    }

    /** Fills the placeholders of each argument; a value is inserted as text and is never looked at again. */
    static List<String> fill(List<String> arguments, Map<String, String> values) {
        List<String> filled = new ArrayList<>();
        for (String argument : arguments) {
            Matcher found = PLACEHOLDER.matcher(argument);
            StringBuilder out = new StringBuilder();
            while (found.find()) {
                found.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(found.group(1), "")));
            }
            found.appendTail(out);
            filled.add(out.toString());
        }
        return filled;
    }
}
