package ca.feedline.service;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Fetches one feed with a strict User-Agent header and a 10 second timeout.
 *
 * <p>The primary feed is tried first. If it does not return valid RSS from this
 * machine (validated by actually parsing the body with Rome), the fallback feed
 * is tried. The returned result always records the URL that produced the data.
 */
public class FeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(FeedFetcher.class);

    private static final String USER_AGENT = "Feedline/1.0 (feedline learn showcase; contact: operator)";

    private static final List<String> ACCEPT = List.of(
            "application/rss+xml, application/xml, application/json, text/xml, text/html, */*");

    private final RestClient restClient;

    public FeedFetcher() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", String.join(", ", ACCEPT))
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Fetch the primary feed and, only if it does not return valid RSS, the fallback.
     *
     * @param primaryUrl    the first feed to try (typically CBS Top Stories)
     * @param fallbackUrl   the second feed to try (typically BBC World)
     * @return the fetch result; {@link #ok()} is true only when a 2xx status and
     *         valid RSS content were returned
     */
    public FeedFetchResult fetch(String primaryUrl, String fallbackUrl) {
        FeedAttempt primary = attempt(primaryUrl);
        if (primary.ok()) {
            return primary.toResult();
        }
        log.warn("Primary feed {} did not return valid RSS (HTTP {}); trying fallback",
                primaryUrl, primary.httpCode());
        FeedAttempt fallback = attempt(fallbackUrl);
        if (fallback.ok()) {
            return fallback.toResult();
        }
        log.error("Fallback feed {} did not return valid RSS (HTTP {})", fallbackUrl, fallback.httpCode());
        return fallback.toResult();
    }

    private FeedAttempt attempt(String url) {
        try {
            String content = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(String.class);
            if (content != null && isValidRss(content)) {
                return new FeedAttempt(200, content, url);
            }
            log.debug("Feed at {} did not return usable content", url);
            return new FeedAttempt(0, null, url);
        } catch (RestClientException ex) {
            log.debug("Failed to fetch {}", url, ex);
            return new FeedAttempt(0, null, url);
        }
    }

    /**
     * True when the body actually parses as a Rome feed with at least one entry.
     *
     * <p>Used to decide whether a HTTP 200 is a real RSS feed or an HTML error
     * page, which is how the primary/fallback selection is made.
     */
    private boolean isValidRss(String content) {
        try (StringReader reader = new StringReader(content)) {
            SyndFeedInput input = new SyndFeedInput();
            input.setXmlHealerOn(false);
            SyndFeed feed = input.build(reader);
            return feed != null && feed.getEntries() != null;
        } catch (Exception ex) {
            log.debug("Feed content is not valid RSS", ex);
            return false;
        }
    }

    /**
     * Outcome of a single fetch attempt.
     *
     * <p>httpCode 0 means "no HTTP response at all" (DNS failure, timeout, ...).
     */
    record FeedAttempt(int httpCode, String content, String url) {

        boolean ok() {
            return httpCode >= 200 && httpCode < 300;
        }

        FeedFetchResult toResult() {
            return new FeedFetchResult(ok() ? httpCode : 0, content, url);
        }
    }
}
