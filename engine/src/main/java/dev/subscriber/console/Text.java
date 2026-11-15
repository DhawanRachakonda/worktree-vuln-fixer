package dev.subscriber.console;

/** Text shown in the console. Values come from reports, repositories and agents, so control characters are never passed on. */
final class Text {

    private Text() {
    }

    /** The text with every control character replaced by {@code ?}, cut to {@code max} characters (with {@code ...}). */
    static String printable(String text, int max) {
        if (text == null) {
            return "-";
        }
        String clean = text.replaceAll("\\p{Cntrl}", "?");
        return clean.length() > max ? clean.substring(0, Math.max(0, max - 3)) + "..." : clean;
    }

    static String printable(String text) {
        return printable(text, 500);
    }
}
