package ca.feedline.service;

import java.time.Duration;

/**
 * Outcome of a single feed fetch attempt.
 *
 * <p>httpCode 0 means "no HTTP response at all" (DNS failure, timeout, ...).
 * {@link #ok()} is true only for a 2xx status together with valid RSS content.
 *
 * <p>The new ETag and Last-Modified values are returned but MUST NOT be persisted
 * until after successful ingest (validator transaction rule).
 *
 * <p>{@code retryAfter} carries a bounded {@code Retry-After} hint from upstream
 * 429/503 responses. The poller uses it as a lower bound for the next poll so a
 * rate-limited feed backs off predictably.
 */
public record FeedFetchResult(
        int httpCode,
        String content,
        String usedUrl,
        String newEtag,
        String newLastModified,
        boolean robotsDisallowed,
        Duration retryAfter
) {

    /**
     * Create a basic fetch result without validators.
     */
    public FeedFetchResult(int httpCode, String content, String usedUrl) {
        this(httpCode, content, usedUrl, null, null, false, null);
    }

    /**
     * Create a fetch result with validators.
     */
    public FeedFetchResult(int httpCode, String content, String usedUrl, String newEtag, String newLastModified) {
        this(httpCode, content, usedUrl, newEtag, newLastModified, false, null);
    }

    /**
     * Backwards-compatible constructor without a retry hint.
     */
    public FeedFetchResult(int httpCode, String content, String usedUrl, String newEtag, String newLastModified,
                           boolean robotsDisallowed) {
        this(httpCode, content, usedUrl, newEtag, newLastModified, robotsDisallowed, null);
    }

    /**
     * Create a robots-disallowed result.
     */
    public static FeedFetchResult robotsDisallowed(String url) {
        return new FeedFetchResult(0, null, url, null, null, true, null);
    }

    /**
     * Create a failure result.
     */
    public static FeedFetchResult failure(int httpCode, String url) {
        return new FeedFetchResult(httpCode, null, url, null, null, false, null);
    }

    /**
     * Create a failure result with an upstream retry hint.
     */
    public static FeedFetchResult failure(int httpCode, String url, Duration retryAfter) {
        return new FeedFetchResult(httpCode, null, url, null, null, false, retryAfter);
    }

    /**
     * Create a 304 Not Modified result.
     */
    public static FeedFetchResult notModified(String url) {
        return new FeedFetchResult(304, null, url, null, null, false, null);
    }

    public boolean ok() {
        return httpCode >= 200 && httpCode < 300;
    }

    public boolean hasNewValidators() {
        return newEtag != null || newLastModified != null;
    }

    /**
     * Whether the upstream signalled a rate-limit or temporary unavailability
     * that should trigger a source backoff.
     */
    public boolean backoffRequested() {
        return httpCode == 429 || httpCode == 503;
    }
}