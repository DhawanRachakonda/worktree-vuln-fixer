package dev.intel.feed.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlText;

/**
 * The {@code <guid>} of an item: its text plus the optional {@code isPermaLink} attribute.
 *
 * @param isPermaLink the attribute value, null when absent
 * @param value       the identifier text
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RssGuid(
        @JacksonXmlProperty(isAttribute = true, localName = "isPermaLink") Boolean isPermaLink,
        @JacksonXmlText String value) {

    /** RSS 2.0 treats a guid as a permalink unless the attribute says {@code false}. */
    public boolean permalink() {
        return isPermaLink == null || isPermaLink;
    }
}
