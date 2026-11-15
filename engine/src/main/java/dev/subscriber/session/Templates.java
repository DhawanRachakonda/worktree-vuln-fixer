package dev.subscriber.session;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {{name}}} placeholders in one pass. A value is inserted as it is and never scanned again, so a path that happens to
 * contain {@code {{something}}} stays exactly as it is, and a placeholder without a value is an error instead of staying in the text.
 */
public final class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

    private Templates() {
    }

    public static String fill(String template, Map<String, String> values) {
        // One pass over the template: a value is inserted as it is and never searched for placeholders again.
        return PLACEHOLDER.matcher(template).replaceAll(match -> {
            String value = values.get(match.group(1));
            if (value == null) {
                throw new IllegalStateException("the template uses {{%s}} but no value was given for it".formatted(match.group(1)));
            }
            return Matcher.quoteReplacement(value);
        });
    }
}
