package dev.intel.feed;

import dev.intel.feed.rss.RssChannel;
import dev.intel.feed.rss.RssDocument;
import dev.intel.feed.rss.RssItem;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Reads an RSS 2.0 document into the typed model ({@link RssDocument}, {@link RssChannel}, {@link RssItem}).
 *
 * <ul>
 * <li>{@link FeedGuard} runs first: no DOCTYPE, bounded depth, element and item counts.</li>
 * <li>{@link ItemSpans} finds the exact text of every item. Each item is then bound on its own, inside the document's
 * {@code <rss><channel>} opening so its namespace prefixes resolve, which keeps the result independent of how items are
 * interleaved with other channel elements (the XML binder drops items that are not contiguous).</li>
 * <li>The binder's parser has DTDs and external entities switched off. Each typed item is paired with its text exactly
 * as received, the raw payload kept for audit.</li>
 * </ul>
 *
 * Failures are reported as {@link FetchFailedException}s with a stable reason and never echo document text.
 */
@Component
public class FeedDocumentReader {

    private static final String CHANNEL_CLOSING = "</channel></rss>";
    private static final RssItem EMPTY_ITEM = new RssItem(null, null, null, null, null, List.of(), null);

    private final FeedGuard guard;
    private final XmlMapper mapper;

    public FeedDocumentReader() {
        this(32, 50_000, 500);
    }

    public FeedDocumentReader(int maxDepth, int maxElements, int maxItems) {
        this.guard = new FeedGuard(maxDepth, maxElements, maxItems);
        this.mapper = newMapper();
    }

    public List<FeedEntry> read(byte[] xml) throws FetchFailedException {
        FeedGuard.Facts facts = guard.check(xml);
        if (!"rss".equals(facts.root())) {
            throw invalid("root element is <" + Safe.text(facts.root(), 40) + ">, expected <rss>", null);
        }
        String text = ItemSpans.decode(xml);
        ItemSpans.Scan scan = ItemSpans.scan(text);
        if (scan.channelOpening() == null) {
            throw invalid("no <channel> element", null);
        }
        if (scan.spans().size() != facts.items()) {
            // The guard and the tokenizer disagree about the items (for example an undetected encoding): fail loudly
            // rather than silently drop or misattribute advisories.
            throw invalid("could not locate the " + facts.items() + " items of the document", null);
        }

        List<FeedEntry> entries = new ArrayList<>(scan.spans().size());
        for (int[] span : scan.spans()) {
            String raw = text.substring(span[0], span[1]);
            entries.add(new FeedEntry(bindItem(scan.channelOpening(), raw), raw));
        }
        return entries;
    }

    /** Binds one item, wrapped in the document's own opening so that namespace prefixes are declared. */
    private RssItem bindItem(String channelOpening, String itemXml) throws FetchFailedException {
        try {
            RssDocument document = mapper.readValue(channelOpening + itemXml + CHANNEL_CLOSING, RssDocument.class);
            if (document == null || document.channel() == null || document.channel().items().isEmpty()) {
                return EMPTY_ITEM; // an empty element: stored as nothing, then skipped as malformed
            }
            return document.channel().items().get(0);
        } catch (JacksonException e) {
            String where = e.getLocation() == null ? ""
                    : " at line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
            throw invalid("an item does not match the RSS format" + where, e);
        }
    }

    private static XmlMapper newMapper() {
        XMLInputFactory input = XMLInputFactory.newFactory();
        input.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        input.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        input.setProperty(XMLInputFactory.IS_COALESCING, true);
        return XmlMapper.builder(XmlFactory.builder().xmlInputFactory(input).build())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .build();
    }

    private static FetchFailedException invalid(String detail, Throwable cause) {
        return new FetchFailedException("invalid feed: " + detail, cause);
    }
}
