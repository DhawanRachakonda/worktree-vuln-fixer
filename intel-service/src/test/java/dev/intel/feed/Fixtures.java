package dev.intel.feed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The saved copy of the real Zero Wire feed (8 items, downloaded unmodified). */
public final class Fixtures {

    public static final String FEED_RESOURCE = "/fixtures/zero-wire-feed.xml";

    private Fixtures() {
    }

    public static byte[] feedBytes() {
        try (InputStream in = Fixtures.class.getResourceAsStream(FEED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test fixture " + FEED_RESOURCE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
