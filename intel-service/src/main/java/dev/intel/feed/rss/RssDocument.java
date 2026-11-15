package dev.intel.feed.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code <rss>} document as the feed sends it. Together with {@link RssChannel}, {@link RssItem} and
 * {@link RssGuid} this mirrors the wire format; values are untrusted text exactly as received (not stripped, not parsed).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RssDocument(RssChannel channel) {
}
