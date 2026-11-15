package dev.intel.feed.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/** The {@code <channel>}: its title and its items. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RssChannel(
        String title,
        @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "item") List<RssItem> items) {

    public RssChannel {
        items = items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }
}
