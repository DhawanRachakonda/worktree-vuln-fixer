package dev.intel.store;

/** An evidence or source link found in a feed item; the label may be absent. */
public record Link(String url, String label) {
}
