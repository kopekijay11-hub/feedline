package ca.feedline.service;

/**
 * Outcome of a single feed fetch attempt.
 *
 * <p>httpCode 0 means "no HTTP response at all" (DNS failure, timeout, ...).
 * {@link #ok()} is true only for a 2xx status together with valid RSS content.
 */
record FeedFetchResult(int httpCode, String content, String usedUrl) {

    boolean ok() {
        return httpCode >= 200 && httpCode < 300;
    }
}
