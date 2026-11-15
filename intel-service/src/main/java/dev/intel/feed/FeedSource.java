package dev.intel.feed;

import java.net.URI;

/** Where the feed document comes from. The production implementation is {@link FeedFetcher}. */
@FunctionalInterface
public interface FeedSource {

    /**
     * @param etag         the stored {@code ETag} validator, or null
     * @param lastModified the stored {@code Last-Modified} validator, or null
     */
    FetchResult fetch(URI uri, String etag, String lastModified) throws FetchException;
}
