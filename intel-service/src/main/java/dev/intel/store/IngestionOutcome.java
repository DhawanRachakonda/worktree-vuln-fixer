package dev.intel.store;

public enum IngestionOutcome {
    /** The feed was fetched and at least one item was created or updated. */
    SUCCESS,
    /** The feed was fetched (or answered 304) and nothing changed. */
    NO_CHANGE,
    /** The fetch or parse failed; stored records are untouched. */
    FAILED,
    /** The destination was refused (not https, not an allowed host, private address, and so on). */
    REFUSED;

    public boolean successful() {
        return this == SUCCESS || this == NO_CHANGE;
    }
}
