package ca.feedline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Duration;
import java.util.List;

/**
 * Feed poller configuration carried in the {@code feed.*} namespace of
 * {@code application.yml} (or an environment override).
 *
 * <p>M2a supports multiple feed sources with polite fetching, conditional GET,
 * retries, timeouts, and body size limits.
 */
@ConfigurationProperties(prefix = "feed")
public class FeedProperties {

    /**
     * List of feed source configurations. Each source has a URL and optional name.
     * If name is not provided, it will be derived from the URL.
     */
    private List<Source> sources;

    /**
     * User-Agent URL for the project. Used in the User-Agent header as:
     * Feedline/0.1 (+<feedline.user-agent-url>)
     */
    private String userAgentUrl = "https://github.com/kopekijay11-hub/feedline";

    /**
     * Connect timeout for HTTP requests.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Read timeout for HTTP requests.
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    /**
     * Maximum response body size in bytes. Requests exceeding this will be aborted.
     */
    private long maxBodyBytes = 5 * 1024 * 1024; // 5 MB

    /**
     * Fixed delay between polling cycles. A duration string (e.g. "60s", "5m")
     * used as the value of the {@code fixedDelayString} attribute of {@link Scheduled}.
     */
    private String pollDelay = "60s";

    private Duration contentFreshnessThreshold = Duration.ofDays(7);

    /**
     * Minimum interval between requests to the same origin (host).
     */
    private Duration perHostInterval = Duration.ofMillis(1000);

    /**
     * Maximum number of retry attempts for network errors and HTTP 5xx responses.
     */
    private int maxRetries = 3;

    /**
     * Maximum number of redirects to follow.
     */
    private int maxRedirects = 3;

    /**
     * Test-only seam that permits loopback targets for local stub servers.
     * Production configuration leaves this disabled.
     */
    private boolean ssrfAllowLoopback = false;

    /**
     * Whether the scheduled poll cycle is active. Readiness must not depend on
     * a heartbeat when scheduling is deliberately disabled (tests, workers).
     */
    private boolean schedulingEnabled = true;

    /**
     * Retention window for ingest skip records.
     */
    private Duration skipRetention = Duration.ofDays(30);

    private Outbox outbox = new Outbox();

    private Admin admin = new Admin();

    public List<Source> getSources() {
        return sources;
    }

    public void setSources(List<Source> sources) {
        this.sources = sources;
    }

    public String getUserAgentUrl() {
        return userAgentUrl;
    }

    public void setUserAgentUrl(String userAgentUrl) {
        this.userAgentUrl = userAgentUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public long getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public String getPollDelay() {
        return pollDelay;
    }

    public void setPollDelay(String pollDelay) {
        this.pollDelay = pollDelay;
    }

    public Duration getContentFreshnessThreshold() {
        return contentFreshnessThreshold;
    }

    public void setContentFreshnessThreshold(Duration contentFreshnessThreshold) {
        this.contentFreshnessThreshold = contentFreshnessThreshold;
    }

    public Duration getPerHostInterval() {
        return perHostInterval;
    }

    public void setPerHostInterval(Duration perHostInterval) {
        this.perHostInterval = perHostInterval;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public boolean isSsrfAllowLoopback() {
        return ssrfAllowLoopback;
    }

    public void setSsrfAllowLoopback(boolean ssrfAllowLoopback) {
        this.ssrfAllowLoopback = ssrfAllowLoopback;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public Duration getSkipRetention() {
        return skipRetention;
    }

    public void setSkipRetention(Duration skipRetention) {
        this.skipRetention = skipRetention;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    /**
     * Outbox configuration.
     */
    public static class Outbox {
        private Cleanup cleanup = new Cleanup();

        @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "Spring binding and internal access")
        public Cleanup getCleanup() {
            return cleanup;
        }

        @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring binding")
        public void setCleanup(Cleanup cleanup) {
            this.cleanup = cleanup;
        }

        public static class Cleanup {
            private boolean enabled = true;
            private Duration publishedRetention = Duration.ofDays(7);
            private int batchSize = 500;
            private String cron = "0 0 * * * *";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public Duration getPublishedRetention() {
                return publishedRetention;
            }

            public void setPublishedRetention(Duration publishedRetention) {
                this.publishedRetention = publishedRetention;
            }

            public int getBatchSize() {
                return batchSize;
            }

            public void setBatchSize(int batchSize) {
                this.batchSize = batchSize;
            }

            public String getCron() {
                return cron;
            }

            public void setCron(String cron) {
                this.cron = cron;
            }
        }
    }

    /**
     * Admin API configuration.
     */
    public static class Admin {
        private boolean enabled = true;
        private String apiKey = "feedline-admin-dev-token";
        private String headerName = "X-Admin-Token";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }
    }

    /**
     * Configuration for a single feed source.
     */
    public static class Source {
        private String name;
        private String url;
        private boolean enabled = true;

        public Source() {
        }

        public Source(String url) {
            this.url = url;
        }

        public Source(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
