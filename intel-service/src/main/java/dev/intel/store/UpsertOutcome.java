package dev.intel.store;

public enum UpsertOutcome {
    /** The item was not stored before. */
    CREATED,
    /** The item existed with different content and was updated. */
    UPDATED,
    /** The item existed with the same content; nothing was written. */
    UNCHANGED
}
