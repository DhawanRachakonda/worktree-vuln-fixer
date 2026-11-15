package dev.intel.store;

/**
 * Narrative sections of a feed item as plain text; any of them may be absent. The overview is the text that precedes
 * the first heading (a status line and the introductory paragraph).
 */
public record Sections(
        String overview,
        String updates,
        String details,
        String risk,
        String affectedDeployments,
        String recommendedAction,
        String coverage,
        String other) {

    public static Sections empty() {
        return new Sections(null, null, null, null, null, null, null, null);
    }
}
