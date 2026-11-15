package dev.intel.feed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Pre-flight safety checks on a feed document, run before it is bound to the typed model.
 *
 * <p>It rejects any DOCTYPE (which also rules out external and recursive entities, in every encoding), rejects documents
 * that are not well-formed, and enforces a maximum element depth, element count and item count so that no oversized or
 * deeply nested structure is ever built. It reads nothing but structure and keeps no document text.
 */
final class FeedGuard {

    /**
     * What the guard learned about an accepted document.
     *
     * @param root  the local name of the root element
     * @param items how many {@code item} elements sit directly under the channel
     */
    record Facts(String root, int items) {
    }

    private final int maxDepth;
    private final int maxElements;
    private final int maxItems;

    FeedGuard(int maxDepth, int maxElements, int maxItems) {
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
        this.maxItems = maxItems;
    }

    Facts check(byte[] xml) throws FetchFailedException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            LimitsHandler handler = new LimitsHandler();
            factory.newSAXParser().parse(new InputSource(new ByteArrayInputStream(xml)), handler);
            return new Facts(handler.root, handler.items);
        } catch (SAXParseException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("DOCTYPE")) {
                throw invalid("DOCTYPE declarations are not allowed", null);
            }
            throw invalid("not well-formed XML at line " + e.getLineNumber() + ", column " + e.getColumnNumber(), e);
        } catch (LimitExceeded e) {
            throw invalid(e.getMessage(), null);
        } catch (SAXException | IOException e) {
            throw invalid("cannot read the document", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser cannot be hardened", e);
        }
    }

    private final class LimitsHandler extends DefaultHandler {
        private String root;
        private int depth;
        private int elements;
        private int items;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            depth++;
            if (root == null) {
                root = localName;
            }
            if (depth > maxDepth) {
                throw new LimitExceeded("elements are nested deeper than " + maxDepth + " levels");
            }
            if (++elements > maxElements) {
                throw new LimitExceeded("more than " + maxElements + " elements");
            }
            if (depth == 3 && "item".equals(localName) && ++items > maxItems) {
                throw new LimitExceeded("more than " + maxItems + " items");
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            depth--;
        }
    }

    private static final class LimitExceeded extends SAXException {
        private static final long serialVersionUID = 1L;

        LimitExceeded(String message) {
            super(message);
        }
    }

    private static FetchFailedException invalid(String detail, Throwable cause) {
        return new FetchFailedException("invalid feed: " + detail, cause);
    }
}
