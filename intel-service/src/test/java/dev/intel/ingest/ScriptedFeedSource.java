package dev.intel.ingest;

import dev.intel.feed.FeedSource;
import dev.intel.feed.FetchException;
import dev.intel.feed.FetchResult;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A feed source that answers from a script and remembers how it was called. */
final class ScriptedFeedSource implements FeedSource {

    record Call(URI uri, String etag, String lastModified) {
    }

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();

    void returns(FetchResult result) {
        script.add(result);
    }

    void fails(Exception failure) {
        script.add(failure);
    }

    List<Call> calls() {
        return calls;
    }

    void clear() {
        script.clear();
        calls.clear();
    }

    @Override
    public FetchResult fetch(URI uri, String etag, String lastModified) throws FetchException {
        calls.add(new Call(uri, etag, lastModified));
        Object next = script.removeFirst();
        if (next instanceof FetchException e) {
            throw e;
        }
        if (next instanceof RuntimeException e) {
            throw e;
        }
        return (FetchResult) next;
    }
}
