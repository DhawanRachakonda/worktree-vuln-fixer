package dev.intel.feed;

import dev.intel.store.Link;
import dev.intel.store.Sections;
import java.time.Instant;
import java.util.List;

/** What the item's HTML content yields: the plain-text sections, the evidence links and the last-reviewed time. */
public record ParsedContent(Sections sections, List<Link> evidenceLinks, Instant lastReviewedAt) {

    public static ParsedContent empty() {
        return new ParsedContent(Sections.empty(), List.of(), null);
    }
}
