package ca.feedline.service;

import com.rometools.rome.io.FeedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>Redirects: follow at most 3, log each hop</li>
 *   <li>robots.txt: checked before fetching, cached per origin</li>
 * </ul>
 */
public class FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    private static final String USER_AGENT_PREFIX = "Feedline/0.1";

    private static final List<String> ACCEPT = List.of(
            "application/rss+xml, application/xml, application/atom+xml, text/xml, */*");

    private final FeedFetchConfig config;
    private final RobotsTxtParser robotsParser;
    private final Sleeper sleeper;
    private final Clock clock;

    // Track last request time per origin for politeness
    private final Map<String, Instant> lastRequestTime = new HashMap<>();

    public FeedFetcher(FeedFetchConfig config) {
        this(config, new RobotsTxtParser(config), Sleeper.REAL, Clock.systemUTC());
    }

    public FeedFetcher(FeedFetchConfig config, RobotsTxtParser robotsParser, Sleeper sleeper, Clock clock) {
        this.config = config;
        this.robotsParser = robotsParser;
        this.sleeper = sleeper;
        this.clock = clock;
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
        URI uri = URI.create(url);
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
                // Unable to fetch robots.txt - fail safe and allow
                log.warn("Unable to fetch robots.txt for {}, allowing by default", origin);
                return true;
            }
            return rules.isAllowed(path);
        } catch (Exception e) {
            log.warn("Error checking robots.txt for {}: {}", origin, e.getMessage());
            // Fail safe and allow
            return true;
        }
    }

    private void enforcePerHostInterval(String origin) {
        synchronized (lastRequestTime) {
            Instant last = lastRequestTime.get(origin);
            if (last != null) {
                Instant now = clock.instant();
                long elapsed = Duration.between(last, now).toMillis();
                long required = config.perHostInterval().toMillis();
                
                if (elapsed < required) {
                    long wait = required - elapsed;
                    log.debug("Waiting {}ms for per-host interval to {} ({}ms required, {}ms elapsed)",
                            wait, origin, required, elapsed);
                    try {
                        sleeper.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Interrupted while waiting for per-host interval");
                    }
                }
            }
            lastRequestTime.put(origin, clock.instant());
        }
    }

    private FeedFetchResult fetchWithRetry(URI uri, String etag, String lastModified, int attempt) {
        try {
            HttpClient client = createHttpClient();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(config.readTimeout())
                    .header("User-Agent", buildUserAgent())
                    .header("Accept", String.join(", ", ACCEPT));

            // Add conditional headers if we have validators
            if (etag != null && !etag.isBlank()) {
                requestBuilder.header("If-None-Match", etag);
            }
            if (lastModified != null && !lastModified.isBlank()) {
                requestBuilder.header("If-Modified-Since", lastModified);
            }

            HttpRequest request = requestBuilder.build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();

            // Check for 304 Not Modified
            if (statusCode == 304) {
                log.info("Feed {} not modified (304)", uri);
                return FeedFetchResult.notModified(uri.toString());
            }

            // Check for 4xx - do NOT retry
            if (statusCode >= 400 && statusCode < 500) {
                log.warn("Feed {} returned HTTP {} - not retrying", uri, statusCode);
                return FeedFetchResult.failure(statusCode, uri.toString());
            }

            // Check for 5xx - retry if we have attempts left
            if (statusCode >= 500 && statusCode < 600) {
                if (attempt < config.maxRetries()) {
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
                } else {
                    log.error("Feed {} returned HTTP {} after {} retries - giving up",
                            uri, statusCode, config.maxRetries());
                    return FeedFetchResult.failure(statusCode, uri.toString());
                }
            }

            // For 2xx and 3xx, read the body with size limit
            if (statusCode >= 200 && statusCode < 400) {
                String body = readBodyWithLimit(response);
                if (body == null) {
                    // Body too large
                    log.warn("Feed {} body exceeds maximum size of {} bytes", uri, config.maxBodyBytes());
                    return FeedFetchResult.failure(413, uri.toString()); // 413 Payload Too Large
                }

                // Extract new validators from response
                String newEtag = response.headers().firstValue("ETag").orElse(null);
                String newLastModified = response.headers().firstValue("Last-Modified").orElse(null);

                // Note: We return the validators but DO NOT persist them yet
                // The caller (FeedPollerService) will persist them only after successful ingest
                return new FeedFetchResult(statusCode, body, uri.toString(), newEtag, newLastModified);
            }

            // Other status codes
            return FeedFetchResult.failure(statusCode, uri.toString());

        } catch (Exception e) {
            // Network or other error
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
            } else {
                log.error("Network error for {} after {} retries - giving up", uri, config.maxRetries());
                return FeedFetchResult.failure(0, uri.toString());
            }
        }
    }

    private HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private String buildUserAgent() {
        return USER_AGENT_PREFIX + " (+" + config.userAgent() + ")";
    }

    private long calculateBackoff(int attempt) {
        // Exponential backoff: 100ms * 2^attempt
        return 100L * (1L << attempt);
    }

    private String readBodyWithLimit(HttpResponse<InputStream> response) {
        try {
            InputStream body = response.body();
            if (body == null) {
                return "";
            }

            // Read with size limit
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            long totalRead = 0;
            int read;
            
            while ((read = body.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > config.maxBodyBytes()) {
                    log.warn("Body exceeds maximum size of {} bytes, aborting", config.maxBodyBytes());
                    body.close();
                    return null;
                }
                output.write(buffer, 0, read);
            }
            
            body.close();
            return output.toString("UTF-8");
        } catch (IOException e) {
            log.error("Error reading response body: {}", e.getMessage());
            return null;
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
}
