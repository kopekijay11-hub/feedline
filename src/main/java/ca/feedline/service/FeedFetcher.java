package ca.feedline.service;

import com.rometools.rome.io.FeedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches feeds with polite behavior: conditional GET, per-host intervals,
 * retries, timeouts, body size limits, and robots.txt checking.
 *
 * <p>Key features:
 * <ul>
 *   <li>User-Agent: Feedline/0.1 (+<feedline.user-agent-url>)</li>
 *   <li>Conditional GET: sends If-None-Match / If-Modified-Since from stored validators</li>
 *   <li>Per-host politeness: minimum interval between requests to the same origin</li>
 *   <li>Timeouts: connect 5s, read 15s (configurable)</li>
 *   <li>Body cap: maximum response body size (configurable, default 5MB)</li>
 *   <li>Retry: network errors and HTTP 5xx only, max 3 attempts, exponential backoff</li>
 *   <li>Redirects: follow at most {@code feed.max-redirects} hops</li>
 *   <li>robots.txt: checked before fetching, cached per origin</li>
 * </ul>
 */
public class FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    private static final String USER_AGENT_PREFIX = "Feedline/0.1";

    private static final int ERROR_DRAIN_BYTES = 8192;

    private static final List<String> ACCEPT = List.of(
            "application/rss+xml, application/xml, application/atom+xml, text/xml, */*");

    private static final Pattern CHARSET_PATTERN = Pattern.compile("charset\\s*=\\s*\"?([a-z0-9._+-]+)\"?");
    private static final Pattern XML_ENCODING_PATTERN = Pattern.compile("encoding\\s*=\\s*[\"']([^\"']+)[\"']");

    private final FeedFetchConfig config;
    private final FeedHttpClient httpClient;
    private final RobotsTxtParser robotsParser;
    private final Sleeper sleeper;
    private final Clock clock;
    private final UrlSafety urlSafety;

    // Track last request time per origin for politeness
    private final Map<String, Instant> lastRequestTime = new HashMap<>();

    public FeedFetcher(FeedFetchConfig config) {
        this(config, new RobotsTxtParser(config), Sleeper.REAL, Clock.systemUTC());
    }

    public FeedFetcher(FeedFetchConfig config, RobotsTxtParser robotsParser, Sleeper sleeper, Clock clock) {
        this(config, new FeedHttpClient(config), robotsParser, sleeper, clock);
    }

    FeedFetcher(FeedFetchConfig config, FeedHttpClient httpClient, RobotsTxtParser robotsParser,
                Sleeper sleeper, Clock clock) {
        this.config = config;
        this.httpClient = httpClient;
        this.robotsParser = robotsParser;
        this.sleeper = sleeper;
        this.clock = clock;
        this.urlSafety = new UrlSafety(config.allowLoopback());
    }

    /**
     * Fetch a feed with all polite behavior.
     *
     * @param url the feed URL
     * @param etag the stored ETag (may be null)
     * @param lastModified the stored Last-Modified (may be null)
     * @return the fetch result
     */
    public FeedFetchResult fetch(String url, String etag, String lastModified) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid URL {}: {}", url, e.getMessage());
            return FeedFetchResult.failure(0, url);
        }
        try {
            urlSafety.validate(uri);
        } catch (UrlSafety.UnsafeUrlException e) {
            log.warn("Unsafe feed URL {}: {}", url, e.getMessage());
            return FeedFetchResult.failure(0, url);
        }
        String origin = FeedFetchConfig.originOf(uri);

        if (origin == null) {
            log.error("Invalid URL: {}", url);
            return FeedFetchResult.failure(0, url);
        }

        // Check robots.txt
        if (!checkRobotsAllowed(origin, uri.getPath())) {
            log.info("Feed {} disallowed by robots.txt for generic agent", url);
            return FeedFetchResult.robotsDisallowed(url);
        }

        // Check per-host interval
        enforcePerHostInterval(origin);

        // Fetch with retries
        return fetchWithRetry(uri, etag, lastModified, 0);
    }

    private boolean checkRobotsAllowed(String origin, String path) {
        try {
            RobotsTxtParser.RobotsRules rules = robotsParser.getRules(origin);
            if (rules == null) {
                log.warn("Unable to fetch robots.txt for {}, disallowing", origin);
                return false;
            }
            return rules.isAllowed(path);
        } catch (Exception e) {
            log.warn("Error checking robots.txt for {}: {}", origin, e.getMessage());
            return false;
        }
    }

    private void enforcePerHostInterval(String origin) {
        long wait;
        synchronized (lastRequestTime) {
            Instant last = lastRequestTime.get(origin);
            wait = 0;
            if (last != null) {
                long elapsed = Duration.between(last, clock.instant()).toMillis();
                long required = config.perHostInterval().toMillis();
                if (elapsed < required) {
                    wait = required - elapsed;
                }
            }
            lastRequestTime.put(origin, clock.instant());
        }
        if (wait > 0) {
            log.debug("Waiting {}ms for per-host interval to {}", wait, origin);
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for per-host interval");
            }
        }
    }

    private FeedFetchResult fetchWithRetry(URI uri, String etag, String lastModified, int attempt) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(config.readTimeout())
                    .header("User-Agent", buildUserAgent())
                    .header("Accept", String.join(", ", ACCEPT));

            if (etag != null && !etag.isBlank()) {
                requestBuilder.header("If-None-Match", etag);
            }
            if (lastModified != null && !lastModified.isBlank()) {
                requestBuilder.header("If-Modified-Since", lastModified);
            }

            HttpRequest request = requestBuilder.build();
            HttpResponse<InputStream> response = httpClient.send(request, this::checkRobotsForHop);
            int statusCode = response.statusCode();
            boolean retryAfterSleep = false;
            FeedFetchResult result;

            try (InputStream body = response.body()) {
                if (statusCode == 304) {
                    log.info("Feed {} not modified (304)", uri);
                    drainBounded(body);
                    result = FeedFetchResult.notModified(uri.toString());
                } else if (statusCode >= 400 && statusCode < 500) {
                    log.warn("Feed {} returned HTTP {} - not retrying", uri, statusCode);
                    drainBounded(body);
                    result = FeedFetchResult.failure(statusCode, uri.toString());
                } else if (statusCode >= 500 && statusCode < 600) {
                    drainBounded(body);
                    if (attempt < config.maxRetries()) {
                        retryAfterSleep = true;
                        result = null;
                    } else {
                        log.error("Feed {} returned HTTP {} after {} retries - giving up",
                                uri, statusCode, config.maxRetries());
                        result = FeedFetchResult.failure(statusCode, uri.toString());
                    }
                } else if (statusCode >= 200 && statusCode < 300) {
                    String bodyText = readBodyWithLimit(body,
                            response.headers().firstValue("Content-Type").orElse(null));
                    if (bodyText == null) {
                        log.warn("Feed {} body exceeds maximum size of {} bytes", uri, config.maxBodyBytes());
                        result = FeedFetchResult.failure(413, uri.toString());
                    } else {
                        String newEtag = response.headers().firstValue("ETag").orElse(null);
                        String newLastModified = response.headers().firstValue("Last-Modified").orElse(null);
                        result = new FeedFetchResult(statusCode, bodyText, uri.toString(),
                                newEtag, newLastModified);
                    }
                } else {
                    drainBounded(body);
                    result = FeedFetchResult.failure(statusCode, uri.toString());
                }
            }

            if (retryAfterSleep) {
                long backoff = calculateBackoff(attempt);
                log.warn("Feed {} returned HTTP {}, retrying in {}ms (attempt {}/{})",
                        uri, statusCode, backoff, attempt + 1, config.maxRetries());
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return FeedFetchResult.failure(statusCode, uri.toString());
                }
                return fetchWithRetry(uri, etag, lastModified, attempt + 1);
            }
            return result;

        } catch (RobotsDisallowedException e) {
            log.info("Feed {} disallowed by robots.txt during fetch", uri);
            return FeedFetchResult.robotsDisallowed(uri.toString());
        } catch (UrlSafety.UnsafeUrlException e) {
            log.warn("Unsafe URL while fetching {}: {}", uri, e.getMessage());
            return FeedFetchResult.failure(0, uri.toString());
        } catch (Exception e) {
            log.debug("Error fetching {}: {}", uri, e.getMessage());
            if (attempt < config.maxRetries()) {
                long backoff = calculateBackoff(attempt);
                log.warn("Network error for {}, retrying in {}ms (attempt {}/{})",
                        uri, backoff, attempt + 1, config.maxRetries());
                try {
                    sleeper.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return FeedFetchResult.failure(0, uri.toString());
                }
                return fetchWithRetry(uri, etag, lastModified, attempt + 1);
            }
            log.error("Network error for {} after {} retries - giving up", uri, config.maxRetries());
            return FeedFetchResult.failure(0, uri.toString());
        }
    }

    private String buildUserAgent() {
        return USER_AGENT_PREFIX + " (+" + config.userAgent() + ")";
    }

    private long calculateBackoff(int attempt) {
        // Exponential backoff: 100ms * 2^attempt
        return 100L * (1L << attempt);
    }

    private String readBodyWithLimit(InputStream body, String contentType) {
        if (body == null) {
            return "";
        }
        try {
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            long totalRead = 0;
            int read;

            while ((read = body.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > config.maxBodyBytes()) {
                    log.warn("Body exceeds maximum size of {} bytes, aborting", config.maxBodyBytes());
                    return null;
                }
                output.write(buffer, 0, read);
            }
            byte[] bytes = output.toByteArray();
            return new String(bytes, charsetOf(contentType, bytes));
        } catch (IOException e) {
            log.error("Error reading response body: {}", e.getMessage());
            return null;
        }
    }

    private static Charset charsetOf(String contentType, byte[] bytes) {
        if (contentType != null) {
            Matcher matcher = CHARSET_PATTERN.matcher(contentType.toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                Charset charset = safeCharset(matcher.group(1));
                if (charset != null) {
                    return charset;
                }
            }
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.ISO_8859_1);
        Matcher matcher = XML_ENCODING_PATTERN.matcher(head);
        if (matcher.find()) {
            Charset charset = safeCharset(matcher.group(1));
            if (charset != null) {
                return charset;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset safeCharset(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            log.warn("Ignoring unsupported response charset {}: {}", name, e.getMessage());
            return null;
        }
    }

    private void drainBounded(InputStream body) {
        if (body == null) {
            return;
        }
        try {
            int limit = (int) Math.min(config.maxBodyBytes(), ERROR_DRAIN_BYTES);
            byte[] buffer = new byte[8192];
            int remaining = limit;
            while (remaining > 0) {
                int read = body.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                remaining -= read;
            }
        } catch (IOException e) {
            log.debug("Error draining response body: {}", e.getMessage());
        }
    }

    private void checkRobotsForHop(URI uri) throws IOException {
        String origin = FeedFetchConfig.originOf(uri);
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        if (origin == null || !checkRobotsAllowed(origin, path)) {
            throw new RobotsDisallowedException(uri.toString());
        }
    }

    /**
     * Clear the per-host interval tracking (useful for testing).
     */
    void clearLastRequestTime() {
        lastRequestTime.clear();
    }

    /**
     * Get the robots parser (for testing).
     */
    RobotsTxtParser getRobotsParser() {
        return robotsParser;
    }

    private static final class RobotsDisallowedException extends IOException {
        private static final long serialVersionUID = 1L;

        RobotsDisallowedException(String uri) {
            super("robots.txt disallowed " + uri);
        }
    }
}
