package dev.intel.query;

import dev.intel.store.KeysetPosition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Encodes and decodes the opaque continuation cursor. It carries the last row's {@code (sort_at, id)}, the fixed
 * look-back boundary of a {@code recent} query (so the window cannot drift between pages) and a fingerprint of the query,
 * so a cursor from a different query is recognised and rejected.
 */
final class CursorCodec {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";

    /** What a decoded cursor says. */
    record Decoded(KeysetPosition after, Instant since, String queryHash) {
    }

    private CursorCodec() {
    }

    static String encode(KeysetPosition after, Instant since, String queryHash) {
        String text = String.join(SEPARATOR, VERSION, Long.toString(micros(after.sortAt())), after.id(),
                since == null ? "-" : Long.toString(micros(since)), queryHash);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException when the text is not a cursor this service produced */
    static Decoded decode(String cursor) {
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = text.split("\\" + SEPARATOR, -1);
            if (parts.length != 5 || !VERSION.equals(parts[0]) || parts[2].isBlank() || parts[4].isBlank()) {
                throw new IllegalArgumentException("unrecognised cursor");
            }
            Instant since = "-".equals(parts[3]) ? null : instant(Long.parseLong(parts[3]));
            return new Decoded(new KeysetPosition(instant(Long.parseLong(parts[1])), parts[2]), since, parts[4]);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unrecognised cursor", e);
        }
    }

    /** A short fingerprint of the parts of a query that a cursor must stay tied to. */
    static String queryHash(String scope, Integer sinceDays, String cveId, String productLower) {
        String canonical = scope + SEPARATOR + (sinceDays == null ? "" : sinceDays) + SEPARATOR
                + (cveId == null ? "" : cveId) + SEPARATOR + (productLower == null ? "" : productLower);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    private static Instant instant(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }
}
