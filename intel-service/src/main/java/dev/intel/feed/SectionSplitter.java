package dev.intel.feed;

import dev.intel.store.Link;
import dev.intel.store.Sections;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Turns the item's {@code content:encoded} HTML into plain text. The HTML is only ever parsed, never rendered, and links
 * are collected as data, never followed.
 *
 * <p>Text before the first {@code h2} is the overview. Known headings fill their own section; any other heading is kept,
 * with its title, in the "other" section so nothing is lost when the feed adds a section. Links are taken from the
 * "Evidence and sources" section only.
 */
@Component
public class SectionSplitter {

    private static final int MAX_LINKS = 100;
    private static final int MAX_URL = 2000;
    private static final int MAX_LABEL = 1000;
    private static final Set<String> CONTAINERS = Set.of("div", "section", "article", "main", "header", "footer",
            "aside");

    private enum Bucket { OVERVIEW, UPDATES, DETAILS, RISK, AFFECTED, ACTION, COVERAGE, EVIDENCE, OTHER }

    public ParsedContent split(String html) {
        if (html == null || html.isBlank()) {
            return ParsedContent.empty();
        }
        Document document = Jsoup.parseBodyFragment(html);
        Map<Bucket, List<String>> text = new LinkedHashMap<>();
        List<Link> links = new ArrayList<>();
        List<Element> overviewBlocks = new ArrayList<>();
        Bucket[] current = {Bucket.OVERVIEW};

        walk(document.body(), node -> {
            if (node instanceof Element e && e.tagName().equals("h2")) {
                String heading = e.text().strip();
                current[0] = bucketFor(heading);
                if (current[0] == Bucket.OTHER && !heading.isEmpty()) {
                    text.computeIfAbsent(Bucket.OTHER, b -> new ArrayList<>()).add("## " + heading);
                }
                return;
            }
            if (current[0] == Bucket.EVIDENCE) {
                if (node instanceof Element e) {
                    collectLinks(e, links);
                }
                return;
            }
            String rendered = render(node);
            if (!rendered.isBlank()) {
                text.computeIfAbsent(current[0], b -> new ArrayList<>()).add(rendered);
            }
            if (current[0] == Bucket.OVERVIEW && node instanceof Element e) {
                overviewBlocks.add(e);
            }
        });

        Sections sections = new Sections(join(text, Bucket.OVERVIEW), join(text, Bucket.UPDATES),
                join(text, Bucket.DETAILS), join(text, Bucket.RISK), join(text, Bucket.AFFECTED),
                join(text, Bucket.ACTION), join(text, Bucket.COVERAGE), join(text, Bucket.OTHER));
        return new ParsedContent(sections, List.copyOf(links), lastReviewed(overviewBlocks));
    }

    /** Visits the block-level nodes in document order, looking inside plain containers. */
    private static void walk(Element parent, java.util.function.Consumer<Node> visitor) {
        for (Node child : parent.childNodes()) {
            if (child instanceof Element e && CONTAINERS.contains(e.tagName()) && !e.children().isEmpty()) {
                walk(e, visitor);
            } else if (child instanceof Element || (child instanceof TextNode t && !t.isBlank())) {
                visitor.accept(child);
            }
        }
    }

    private static Bucket bucketFor(String heading) {
        return switch (heading.toLowerCase(Locale.ROOT)) {
            case "updates" -> Bucket.UPDATES;
            case "details" -> Bucket.DETAILS;
            case "risk" -> Bucket.RISK;
            case "affected deployments" -> Bucket.AFFECTED;
            case "recommended action" -> Bucket.ACTION;
            case "cloudflare coverage" -> Bucket.COVERAGE;
            case "evidence and sources" -> Bucket.EVIDENCE;
            default -> Bucket.OTHER;
        };
    }

    private static String render(Node node) {
        if (node instanceof TextNode t) {
            return t.text().strip();
        }
        Element e = (Element) node;
        return switch (e.tagName()) {
            case "ul", "ol" -> {
                List<String> items = new ArrayList<>();
                for (Element li : e.children()) {
                    String text = li.text().strip();
                    if (!text.isEmpty()) {
                        items.add("- " + text);
                    }
                }
                yield String.join("\n", items);
            }
            case "table" -> {
                List<String> rows = new ArrayList<>();
                for (Element row : e.select("tr")) {
                    List<String> cells = new ArrayList<>();
                    for (Element cell : row.select("th,td")) {
                        cells.add(cell.text().strip());
                    }
                    rows.add(String.join(" | ", cells));
                }
                yield String.join("\n", rows);
            }
            default -> e.text().strip();
        };
    }

    private static String join(Map<Bucket, List<String>> text, Bucket bucket) {
        List<String> parts = text.get(bucket);
        return parts == null || parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static void collectLinks(Element block, List<Link> links) {
        for (Element a : block.select("a[href]")) {
            if (links.size() >= MAX_LINKS) {
                return;
            }
            String href = a.attr("href").strip();
            if (!isHttpUrl(href) || href.length() > MAX_URL || links.stream().anyMatch(l -> l.url().equals(href))) {
                continue;
            }
            String label = a.text().strip();
            if (label.length() > MAX_LABEL) {
                label = label.substring(0, MAX_LABEL);
            }
            links.add(new Link(href, label.isEmpty() ? null : label));
        }
    }

    static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The {@code datetime} of the {@code time} element that sits next to the text "Last reviewed". */
    private static Instant lastReviewed(List<Element> overviewBlocks) {
        for (Element block : overviewBlocks) {
            for (Element time : block.select("time[datetime]")) {
                Element holder = time.parent();
                if (holder != null && holder.text().toLowerCase(Locale.ROOT).contains("last reviewed")) {
                    Instant parsed = parseDateTime(time.attr("datetime").strip());
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    static Instant parseDateTime(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next shape
        }
        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
