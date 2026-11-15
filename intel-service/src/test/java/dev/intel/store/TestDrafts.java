package dev.intel.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

final class TestDrafts {

    static final String SOURCE = "zero-wire";
    static final Instant T0 = Instant.parse("2026-11-09T10:00:00Z");

    private TestDrafts() {
    }

    static String sha(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static VulnerabilityDraft draft(String itemId, String title, Instant published, Instant reviewed,
            List<String> cves, List<String> tags, String affected, String contentSeed) {
        return new VulnerabilityDraft(SOURCE, itemId, title, "https://radar.cloudflare.com/security/zero-wire/" + itemId,
                published, reviewed, "summary of " + title,
                new Sections("overview of " + title, "updates", "details of " + title, "risk", affected,
                        "recommended action", "coverage", null),
                cves, tags,
                List.of(new Link("https://example.org/first/" + itemId, "First"),
                        new Link("https://example.org/second/" + itemId, null)),
                "<item>raw " + itemId + " & more</item>", sha(contentSeed));
    }

    static VulnerabilityDraft simple(String itemId) {
        return draft(itemId, "Title " + itemId, T0.minusSeconds(3600), T0.minusSeconds(1800),
                List.of("CVE-2026-000" + itemId.length()), List.of("Tag"), "affected " + itemId, "seed-" + itemId);
    }
}
