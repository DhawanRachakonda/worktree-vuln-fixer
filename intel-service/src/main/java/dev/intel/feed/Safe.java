package dev.intel.feed;

/**
 * Makes text that the feed's origin controls safe to put in a log line or a stored failure reason: only printable ASCII
 * survives, everything else becomes {@code ?}, and the length is capped. Used for the few values an origin can choose
 * (a redirect target, a host name, a content type, an element name); it keeps messages useful to an operator without
 * letting an origin place arbitrary text, line breaks or control characters into logs and records.
 */
final class Safe {

    private Safe() {
    }

    static String text(String value, int max) {
        if (value == null) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), max) + 3);
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            out.append(c >= 0x20 && c <= 0x7E ? c : '?');
        }
        if (value.length() > max) {
            out.append("...");
        }
        return out.toString();
    }
}
