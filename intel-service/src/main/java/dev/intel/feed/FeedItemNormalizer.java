package dev.intel.feed;

import dev.intel.feed.rss.RssGuid;
import dev.intel.feed.rss.RssItem;
import dev.intel.store.VulnerabilityDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/**
 * Normalizes a {@link FeedEntry} into the {@link VulnerabilityDraft} that is stored. All text stays untrusted data:
 * nothing here interprets or acts on it.
 *
 * <p>The feed's {@code category} values mix tags, a status label ("Needs attention") and CVE ids; the CVE ids join the
 * CVE list and the rest become tags. The fingerprint covers the normalized title, description, content and categories,
 * so a corrected advisory is detected and a whitespace-only difference is not.
 */
@Component
public class FeedItemNormalizer {

    private static final Pattern CVE = Pattern.compile("(?i)\\bCVE-\\d{4}-\\d{4,19}\\b");
    private static final Pattern CVE_WHOLE = Pattern.compile("(?i)^CVE-\\d{4}-\\d{4,19}$");
    private static final int MAX_TITLE = 1000;
    private static final int MAX_ID = 500;
    private static final int MAX_URL = 2000;
    private static final int MAX_TAG = 200;
    private static final int MAX_TAGS = 50;
    private static final int MAX_CVES = 200;
    private static final String FINGERPRINT_VERSION = "v1";

    private final SectionSplitter splitter;

    public FeedItemNormalizer(SectionSplitter splitter) {
        this.splitter = splitter;
    }

    public VulnerabilityDraft normalize(String sourceName, FeedEntry entry) throws MalformedItemException {
        RssItem item = entry.item();
        String title = clip(item.title(), MAX_TITLE);
        if (title == null) {
            throw new MalformedItemException("item has no title");
        }
        String guid = guidText(item);
        String link = text(item.link());
        String itemId = guid != null ? guid : link;
        if (itemId == null) {
            throw new MalformedItemException("item has neither a guid nor a link");
        }
        if (itemId.length() > MAX_ID) {
            throw new MalformedItemException("item identifier is longer than " + MAX_ID + " characters");
        }
        String url = usableLink(item, link, guid);
        if (url == null) {
            throw new MalformedItemException("item has no usable link");
        }

        ParsedContent content = splitter.split(item.contentEncoded());
        List<String> categories = categories(item);
        List<String> tags = new ArrayList<>();
        for (String category : categories) {
            if (!CVE_WHOLE.matcher(category).matches()) {
                tags.add(clip(category, MAX_TAG));
            }
        }
        return new VulnerabilityDraft(sourceName, itemId, title, url, parseRfc822(text(item.pubDate())),
                content.lastReviewedAt(), text(item.description()), content.sections(), cveIds(item, categories),
                dedupeTags(tags), content.evidenceLinks(), entry.rawXml(), fingerprint(item));
    }

    private static String guidText(RssItem item) {
        RssGuid guid = item.guid();
        return guid == null ? null : text(guid.value());
    }

    private static String usableLink(RssItem item, String link, String guid) {
        boolean guidIsLink = item.guid() != null && item.guid().permalink();
        for (String candidate : new String[] {link, guidIsLink ? guid : null}) {
            if (candidate != null && candidate.length() <= MAX_URL && SectionSplitter.isHttpUrl(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> categories(RssItem item) {
        List<String> categories = new ArrayList<>();
        for (String category : item.categories()) {
            String text = text(category);
            if (text != null) {
                categories.add(text);
            }
        }
        return categories;
    }

    private static List<String> cveIds(RssItem item, List<String> categories) {
        StringBuilder haystack = new StringBuilder();
        haystack.append(item.title()).append('\n').append(item.description()).append('\n');
        categories.forEach(c -> haystack.append(c).append('\n'));
        if (item.contentEncoded() != null) {
            haystack.append(Jsoup.parse(item.contentEncoded()).text());
        }
        TreeSet<String> ids = new TreeSet<>();
        Matcher m = CVE.matcher(haystack);
        while (m.find() && ids.size() < MAX_CVES) {
            ids.add(m.group().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(ids);
    }

    private static List<String> dedupeTags(List<String> tags) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (String tag : tags) {
            if (tag != null && !tag.isBlank() && unique.size() < MAX_TAGS) {
                unique.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag.strip());
            }
        }
        return List.copyOf(unique.values());
    }

    static Instant parseRfc822(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return SectionSplitter.parseDateTime(value.strip());
        }
    }

    /** SHA-256 over the whitespace-normalized title, description, content and sorted categories. */
    static String fingerprint(RssItem item) {
        StringBuilder canonical = new StringBuilder(FINGERPRINT_VERSION).append('\n')
                .append(collapse(item.title())).append('\n')
                .append(collapse(item.description())).append('\n')
                .append(collapse(item.contentEncoded())).append('\n');
        item.categories().stream().map(FeedItemNormalizer::collapse).sorted()
                .forEach(c -> canonical.append(c).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whitespace between tags is layout only; any other run of whitespace counts as one space. */
    private static String collapse(String s) {
        return s == null ? "" : s.strip().replaceAll(">\\s+<", "><").replaceAll("\\s+", " ");
    }

    private static String text(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        String stripped = text(s);
        if (stripped == null) {
            return null;
        }
        return stripped.length() > max ? stripped.substring(0, max) : stripped;
    }
}
