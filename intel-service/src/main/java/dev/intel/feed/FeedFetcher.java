package dev.intel.feed;

import dev.intel.config.IntelProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches the feed under hard limits. Every destination, including each redirect target, is validated first; the
 * response size, the total time (headers and body) and the content type are bounded; conditional-request validators
 * are sent when known. Failures are reported as {@link FetchException}s whose reason text can be stored as is.
 */
public final class FeedFetcher implements FeedSource {

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final Set<String> XML_TYPES = Set.of("application/rss+xml", "application/xml", "text/xml",
            "application/atom+xml");
    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();

    private final HttpClient http;
    private final DestinationValidator validator;
    private final IntelProperties.Feed feed;

    /** @param http a client that does not follow redirects itself */
    public FeedFetcher(HttpClient http, DestinationValidator validator, IntelProperties.Feed feed) {
        this.http = http;
        this.validator = validator;
        this.feed = feed;
    }

    @Override
    public FetchResult fetch(URI uri, String etag, String lastModified) throws FetchException {
        long deadline = System.nanoTime() + feed.totalTimeout().toNanos();
        boolean conditional = etag != null || lastModified != null;
        URI current = uri;
        validator.validate(current, "feed URL");

        int redirects = 0;
        while (true) {
            HttpResponse<InputStream> response = send(current, etag, lastModified, deadline);
            int status = response.statusCode();
            String host = current.getHost();

            if (REDIRECTS.contains(status)) {
                closeQuietly(response.body());
                String location = response.headers().firstValue("Location").orElseThrow(() -> new FetchFailedException(
                        "HTTP " + status + " redirect without a Location header from '" + host + "'"));
                if (++redirects > feed.maxRedirects()) {
                    throw new FetchFailedException("too many redirects (more than " + feed.maxRedirects() + ")");
                }
                URI next;
                try {
                    next = current.resolve(location.strip());
                } catch (IllegalArgumentException e) {
                    throw new FetchFailedException("invalid redirect location from '" + host + "'", e);
                }
                validator.validate(next, "redirect target");
                current = next;
                continue;
            }
            if (status == 304) {
                closeQuietly(response.body());
                if (!conditional) {
                    throw new FetchFailedException("HTTP 304 received although no conditional request was sent");
                }
                return new FetchResult.NotModified();
            }
            if (status != 200) {
                closeQuietly(response.body());
                throw new FetchFailedException("HTTP " + status + " from '" + host + "'");
            }

            String contentType = mimeType(response);
            if (!XML_TYPES.contains(contentType)) {
                closeQuietly(response.body());
                throw new FetchFailedException("unexpected content type '" + Safe.text(contentType, 60) + "' from '"
                        + host + "'");
            }
            long max = feed.maxBytes().toBytes();
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > max) {
                closeQuietly(response.body());
                throw new FetchFailedException(
                        "response of " + declared + " bytes exceeds the limit of " + max + " bytes");
            }
            byte[] body = readBody(response.body(), deadline, current);
            return new FetchResult.Fetched(body, response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null), current);
        }
    }

    private HttpResponse<InputStream> send(URI uri, String etag, String lastModified, long deadline)
            throws FetchFailedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw timedOut(uri);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET()
                .timeout(Duration.ofNanos(remaining))
                .header("User-Agent", feed.userAgent())
                .header("Accept", "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8");
        if (etag != null) {
            request.header("If-None-Match", etag);
        }
        if (lastModified != null) {
            request.header("If-Modified-Since", lastModified);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw timedOut(uri);
        } catch (IOException e) {
            throw new FetchFailedException("cannot fetch from '" + uri.getHost() + "': " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchFailedException("interrupted while fetching from '" + uri.getHost() + "'", e);
        }
    }

    private byte[] readBody(InputStream in, long deadline, URI uri) throws FetchFailedException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remainingMillis <= 0) {
            closeQuietly(in);
            throw timedOut(uri);
        }
        long max = feed.maxBytes().toBytes();
        Future<byte[]> reading = READERS.submit(() -> readUpTo(in, max));
        try {
            return reading.get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            closeQuietly(in); // unblocks the reader
            reading.cancel(true);
            throw timedOut(uri);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BodyTooLargeException) {
                throw new FetchFailedException("response exceeds the limit of " + max + " bytes");
            }
            throw new FetchFailedException("error reading the response from '" + uri.getHost() + "': "
                    + describe(cause), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reading.cancel(true);
            throw new FetchFailedException("interrupted while reading from '" + uri.getHost() + "'", e);
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] readUpTo(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new BodyTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private FetchFailedException timedOut(URI uri) {
        return new FetchFailedException("timed out after " + feed.totalTimeout() + " fetching from '" + uri.getHost()
                + "'");
    }

    private static String mimeType(HttpResponse<?> response) {
        String header = response.headers().firstValue("Content-Type").orElse("(none)");
        int semicolon = header.indexOf(';');
        return (semicolon < 0 ? header : header.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return Safe.text(t.getClass().getSimpleName() + (message == null ? "" : ": " + message), 200);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
