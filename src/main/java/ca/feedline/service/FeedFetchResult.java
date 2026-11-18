package ca.feedline.service;

/**
 * Outcome of a single feed fetch attempt.
 *
 * <p>httpCode 0 means "no HTTP response at all" (DNS failure, timeout, ...).
 * {@link #ok()} is true only for a 2xx status together with valid RSS content.
 *
 * <p>The new ETag and Last-Modified values are returned but MUST NOT be persisted
 * until after successful ingest (validator transaction rule).
 */
public record FeedFetchResult(
        int httpCode,
        String content,
        String usedUrl,
        String newEtag,
        String newLastModified,
        boolean robotsDisallowed
) {

    /**
     * Create a successful fetch result.
     */
    public FeedFetchResult {
        // Default robotsDisallowed to false
    }

    /**
     * Create a basic fetch result without validators.
     */
    public FeedFetchResult(int httpCode, String content, String usedUrl) {
        this(httpCode, content, usedUrl, null, null, false);
    }

    /**
     * Create a fetch result with validators.
     */
    public FeedFetchResult(int httpCode, String content, String usedUrl, String newEtag, String newLastModified) {
        this(httpCode, content, usedUrl, newEtag, newLastModified, false);
    }

    /**
     * Create a robots-disallowed result.
     */
    public static FeedFetchResult robotsDisallowed(String url) {
        return new FeedFetchResult(0, null, url, null, null, true);
    }

    /**
     * Create a failure result.
     */
    public static FeedFetchResult failure(int httpCode, String url) {
        return new FeedFetchResult(httpCode, null, url, null, null, false);
    }

    /**
     * Create a 304 Not Modified result.
     */
    public static FeedFetchResult notModified(String url) {
        return new FeedFetchResult(304, null, url, null, null, false);
    }

    public boolean ok() {
        return httpCode >= 200 && httpCode < 300;
    }

    public boolean hasNewValidators() {
        return newEtag != null || newLastModified != null;
    }
}
