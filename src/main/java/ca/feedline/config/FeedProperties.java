package ca.feedline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feed poller configuration carried in the {@code feed.*} namespace of
 * {@code application.yml} (or an environment override).
 *
 * <p>The poller always starts with {@link #getPrimaryFeedUrl()} and, only if it
 * does not return valid RSS from this machine, falls back to
 * {@link #getFallbackFeedUrl()}.
 */
@ConfigurationProperties(prefix = "feed")
public class FeedProperties {

    private String primaryFeedUrl = "https://www.cbc.ca/webfeed/rss/rss-topstories";

    private String fallbackFeedUrl = "https://feeds.bbci.co.uk/news/world/rss.xml";

    /**
     * Fixed delay between polling cycles. A duration string (e.g. "60s", "5m")
     * used as the value of the {@code fixedDelayString} attribute of {@link Scheduled}.
     */
    private String pollDelay = "60s";

    public String getPrimaryFeedUrl() {
        return primaryFeedUrl;
    }

    public void setPrimaryFeedUrl(String primaryFeedUrl) {
        this.primaryFeedUrl = primaryFeedUrl;
    }

    public String getFallbackFeedUrl() {
        return fallbackFeedUrl;
    }

    public void setFallbackFeedUrl(String fallbackFeedUrl) {
        this.fallbackFeedUrl = fallbackFeedUrl;
    }

    public String getPollDelay() {
        return pollDelay;
    }

    public void setPollDelay(String pollDelay) {
        this.pollDelay = pollDelay;
    }
}
