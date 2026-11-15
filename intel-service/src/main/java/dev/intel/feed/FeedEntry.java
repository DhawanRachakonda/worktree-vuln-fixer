package dev.intel.feed;

import dev.intel.feed.rss.RssItem;

/**
 * One item of the feed: the typed item and its text exactly as received, kept as the audit copy.
 *
 * @param rawXml the item's text as it appeared in the response
 */
public record FeedEntry(RssItem item, String rawXml) {
}
