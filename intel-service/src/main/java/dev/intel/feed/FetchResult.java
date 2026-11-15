package dev.intel.feed;

import java.net.URI;

/** The successful outcomes of a fetch. */
public sealed interface FetchResult {

    /** The origin answered 304 to a conditional request: nothing changed since the stored validators. */
    record NotModified() implements FetchResult {
    }

    /** The feed document, with the validators to store for the next conditional request. */
    record Fetched(byte[] body, String etag, String lastModified, URI finalUri) implements FetchResult {
    }
}
