package dev.intel.feed.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * An RSS {@code <item>} as received.
 *
 * @param pubDate        the {@code pubDate} text (RFC 822), not yet parsed
 * @param categories     every {@code <category>}: the feed mixes tags, a status label and CVE ids here
 * @param contentEncoded the {@code content:encoded} HTML body of the item
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RssItem(
        String title,
        String link,
        RssGuid guid,
        String pubDate,
        String description,
        @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "category") List<String> categories,
        @JacksonXmlProperty(localName = "encoded", namespace = RssNamespaces.CONTENT) String contentEncoded) {

    public RssItem {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
