package ca.feedline.service;

import java.net.URI;
import java.time.Duration;

/**
 * Immutable configuration for a single feed fetch operation.
 *
 * <p>This class holds all the configuration needed to fetch a feed, including
 * HTTP timeouts, body size limits, retry policy, and redirect limits.
 */
public record FeedFetchConfig(
        String userAgent,
        Duration connectTimeout,
        Duration readTimeout,
        long maxBodyBytes,
        int maxRedirects,
        Duration perHostInterval,
        int maxRetries,
        boolean allowLoopback
) {
    public FeedFetchConfig(String userAgent, Duration connectTimeout, Duration readTimeout, long maxBodyBytes,
                           int maxRedirects, Duration perHostInterval, int maxRetries) {
        this(userAgent, connectTimeout, readTimeout, maxBodyBytes, maxRedirects,
                perHostInterval, maxRetries, false);
    }

    public FeedFetchConfig {
        // Validate inputs
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be null or blank");
        }
        if (connectTimeout == null || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be non-negative");
        }
        if (readTimeout == null || readTimeout.isNegative()) {
            throw new IllegalArgumentException("readTimeout must be non-negative");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes must be positive");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must be non-negative");
        }
        if (perHostInterval == null || perHostInterval.isNegative()) {
            throw new IllegalArgumentException("perHostInterval must be non-negative");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative");
        }
    }

    /**
     * Extract the origin (host) from a URI.
     *
     * @param uri the URI to extract the origin from
     * @return the origin (scheme + "://" + host)
     */
    public static String originOf(URI uri) {
        if (uri == null) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            return null;
        }
        int port = uri.getPort();
        if (port > 0) {
            return scheme + "://" + host + ":" + port;
        }
        return scheme + "://" + host;
    }
}
