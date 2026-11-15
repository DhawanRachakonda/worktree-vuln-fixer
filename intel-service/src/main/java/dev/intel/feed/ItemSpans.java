package dev.intel.feed;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates, in the decoded text of an RSS document, the exact text of each {@code channel/item} element and the opening of
 * the document ({@code <rss ...><channel>}) those items live in. Binding each item on its own, inside that opening so
 * that its namespace prefixes resolve, avoids any dependence on how items are interleaved with other channel elements.
 *
 * <p>It runs only on documents the guard already accepted as well-formed, so it can be a plain tokenizer: it skips
 * comments, processing instructions and CDATA sections (which may legitimately contain text like {@code </item>}), and
 * reads quoted attribute values (which may contain {@code >}).
 */
final class ItemSpans {

    private static final Pattern XML_DECLARATION_ENCODING =
            Pattern.compile("^<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']");

    private ItemSpans() {
    }

    /**
     * @param channelOpening the root start tag followed by the channel start tag, or null when there is no channel
     * @param spans          start (inclusive) and end (exclusive) offsets of every item that is a child of the channel
     */
    record Scan(String channelOpening, List<int[]> spans) {
    }

    /** Decodes the document the way its byte-order mark or XML declaration says, defaulting to UTF-8. */
    static String decode(byte[] xml) {
        Charset charset = StandardCharsets.UTF_8;
        int skip = 0;
        if (xml.length >= 3 && (xml[0] & 0xFF) == 0xEF && (xml[1] & 0xFF) == 0xBB && (xml[2] & 0xFF) == 0xBF) {
            skip = 3;
        } else if (xml.length >= 2 && (xml[0] & 0xFF) == 0xFE && (xml[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (xml.length >= 2 && (xml[0] & 0xFF) == 0xFF && (xml[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            skip = 2;
        } else {
            String head = new String(xml, 0, Math.min(200, xml.length), StandardCharsets.ISO_8859_1);
            Matcher m = XML_DECLARATION_ENCODING.matcher(head);
            if (m.find()) {
                try {
                    charset = Charset.forName(m.group(1));
                } catch (RuntimeException unsupported) {
                    // keep UTF-8; a wrong guess shows up as an item-count mismatch with the guard
                }
            }
        }
        return new String(xml, skip, xml.length - skip, charset);
    }

    static Scan scan(String xml) {
        List<int[]> spans = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();
        String rootOpening = null;
        String channelOpening = null;
        int itemStart = -1;
        int n = xml.length();
        int i = 0;
        while (i >= 0 && i < n) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                break;
            }
            if (xml.startsWith("<!--", lt)) {
                i = skipPast(xml, "-->", lt + 4);
            } else if (xml.startsWith("<![CDATA[", lt)) {
                i = skipPast(xml, "]]>", lt + 9);
            } else if (xml.startsWith("<?", lt)) {
                i = skipPast(xml, "?>", lt + 2);
            } else if (xml.startsWith("<!", lt)) {
                i = skipPast(xml, ">", lt + 2);
            } else if (xml.startsWith("</", lt)) {
                int end = tagEnd(xml, lt);
                if (end < 0) {
                    break;
                }
                String name = xml.substring(lt + 2, end).strip();
                open.pollLast();
                if (open.size() == 2 && "item".equals(name) && itemStart >= 0) {
                    spans.add(new int[] {itemStart, end + 1});
                    itemStart = -1;
                }
                i = end + 1;
            } else {
                int end = tagEnd(xml, lt);
                if (end < 0) {
                    break;
                }
                boolean selfClosing = xml.charAt(end - 1) == '/';
                String name = tagName(xml, lt + 1, end);
                if (open.isEmpty() && rootOpening == null) {
                    rootOpening = xml.substring(lt, end + 1);
                } else if (open.size() == 1 && "channel".equals(name) && channelOpening == null && !selfClosing) {
                    channelOpening = rootOpening + xml.substring(lt, end + 1);
                } else if (open.size() == 2 && "item".equals(name)) {
                    if (selfClosing) {
                        spans.add(new int[] {lt, end + 1});
                    } else {
                        itemStart = lt;
                    }
                }
                if (!selfClosing) {
                    open.addLast(name);
                }
                i = end + 1;
            }
        }
        return new Scan(channelOpening, spans);
    }

    /** Offset just after the terminator, or -1 when it is missing (the caller then stops scanning). */
    private static int skipPast(String s, String terminator, int from) {
        int e = s.indexOf(terminator, from);
        return e < 0 ? -1 : e + terminator.length();
    }

    /** Index of the {@code >} that closes the tag starting at {@code lt}, ignoring any inside quoted values. */
    private static int tagEnd(String s, int lt) {
        char quote = 0;
        for (int i = lt + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            }
        }
        return -1;
    }

    private static String tagName(String s, int from, int end) {
        int i = from;
        while (i < end && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '/') {
            i++;
        }
        return s.substring(from, i);
    }
}
