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

    private Poller poller = new Poller();

    private Robots robots = new Robots();

    private Normalizer normalizer = new Normalizer();

    private Items items = new Items();

    private Cluster cluster = new Cluster();

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

    public Poller getPoller() {
        return poller;
    }

    public void setPoller(Poller poller) {
        this.poller = poller == null ? new Poller() : poller;
    }

    public Robots getRobots() {
        return robots;
    }

    public void setRobots(Robots robots) {
        this.robots = robots == null ? new Robots() : robots;
    }

    public Normalizer getNormalizer() {
        return normalizer;
    }

    public void setNormalizer(Normalizer normalizer) {
        this.normalizer = normalizer == null ? new Normalizer() : normalizer;
    }

    public Items getItems() {
        return items;
    }

    public void setItems(Items items) {
        this.items = items == null ? new Items() : items;
    }

    /**
     * Outbox relay configuration.
     *
     * <p>Values are clamped in the setters so a misconfigured environment cannot
     * produce an unbounded claim, a zero retry budget, or a non-positive send wait.
     */
    public static class Outbox {
        private static final String DEFAULT_TOPIC = "feedline.items.ingested";

        private boolean enabled = true;
        private String topic = DEFAULT_TOPIC;
        private String pollDelay = "2000";
        private int batchSize = 50;
        private int maxAttempts = 5;
        private long initialBackoffMs = 1000;
        private long maxBackoffMs = 60000;
        private long sendTimeoutMs = 5000;

        private Cleanup cleanup = new Cleanup();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic == null || topic.isBlank() ? DEFAULT_TOPIC : topic;
        }

        public String getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(String pollDelay) {
            this.pollDelay = pollDelay == null || pollDelay.isBlank() ? "2000" : pollDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = Math.max(1, Math.min(batchSize, 1000));
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = Math.max(1, Math.min(maxAttempts, 100));
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = Math.max(1, Math.min(initialBackoffMs, 600_000));
            if (this.maxBackoffMs < this.initialBackoffMs) {
                this.maxBackoffMs = this.initialBackoffMs;
            }
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = Math.max(this.initialBackoffMs, Math.min(maxBackoffMs, 3_600_000));
        }

        public long getSendTimeoutMs() {
            return sendTimeoutMs;
        }

        public void setSendTimeoutMs(long sendTimeoutMs) {
            this.sendTimeoutMs = Math.max(1, Math.min(sendTimeoutMs, 60_000));
        }

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
        private String apiKey;
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
     * Poller lease configuration.
     */
    public static class Poller {
        private Duration leaseDuration = Duration.ofMinutes(2);
        private String workerId = "";
        private Duration backoffBase = Duration.ofSeconds(30);
        private Duration maxBackoff = Duration.ofHours(1);
        private Duration shutdownTimeout = Duration.ofSeconds(10);

        public Duration getLeaseDuration() {
            return leaseDuration;
        }

        public void setLeaseDuration(Duration leaseDuration) {
            this.leaseDuration = leaseDuration == null ? Duration.ofMinutes(2) : leaseDuration;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId == null ? "" : workerId;
        }

        /**
         * Base delay for the first consecutive failure. The delay doubles per
         * consecutive failure up to {@link #maxBackoff}. A server-provided
         * {@code Retry-After} is honoured when it is larger, still capped at
         * {@link #maxBackoff}.
         */
        public Duration getBackoffBase() {
            return backoffBase;
        }

        public void setBackoffBase(Duration backoffBase) {
            this.backoffBase = backoffBase == null || backoffBase.isNegative() || backoffBase.isZero()
                    ? Duration.ofSeconds(30) : backoffBase;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            Duration candidate = maxBackoff == null || maxBackoff.isNegative() || maxBackoff.isZero()
                    ? Duration.ofHours(1) : maxBackoff;
            this.maxBackoff = candidate.compareTo(this.backoffBase) < 0 ? this.backoffBase : candidate;
        }

        /**
         * Upper bound on how long {@link ca.feedline.service.FeedPollerService}
         * waits for an in-flight poll cycle before releasing leases on shutdown.
         */
        public Duration getShutdownTimeout() {
            return shutdownTimeout;
        }

        public void setShutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout == null || shutdownTimeout.isNegative() || shutdownTimeout.isZero()
                    ? Duration.ofSeconds(10) : shutdownTimeout;
        }
    }

    /**
     * Robots.txt cache configuration.
     */
    public static class Robots {
        private int cacheMaxEntries = 256;
        private Duration cacheTtl = Duration.ofHours(1);

        public int getCacheMaxEntries() {
            return cacheMaxEntries;
        }

        public void setCacheMaxEntries(int cacheMaxEntries) {
            this.cacheMaxEntries = cacheMaxEntries <= 0 ? 256 : cacheMaxEntries;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl == null ? Duration.ofHours(1) : cacheTtl;
        }
    }

    /**
     * FeedNormalizer field caps.
     */
    public static class Normalizer {
        private int titleMaxLength = 1000;
        private int summaryMaxLength = 4000;
        private int linkMaxLength = 1024;
        private int guidMaxLength = 512;

        public int getTitleMaxLength() {
            return titleMaxLength;
        }

        public void setTitleMaxLength(int titleMaxLength) {
            this.titleMaxLength = titleMaxLength <= 0 ? 1000 : titleMaxLength;
        }

        public int getSummaryMaxLength() {
            return summaryMaxLength;
        }

        public void setSummaryMaxLength(int summaryMaxLength) {
            this.summaryMaxLength = summaryMaxLength <= 0 ? 4000 : summaryMaxLength;
        }

        public int getLinkMaxLength() {
            return linkMaxLength;
        }

        public void setLinkMaxLength(int linkMaxLength) {
            this.linkMaxLength = linkMaxLength <= 0 ? 1024 : linkMaxLength;
        }

        public int getGuidMaxLength() {
            return guidMaxLength;
        }

        public void setGuidMaxLength(int guidMaxLength) {
            this.guidMaxLength = guidMaxLength <= 0 ? 512 : guidMaxLength;
        }
    }

    /**
     * Items API query defaults.
     *
     * <p>Values are clamped so a misconfigured environment cannot produce an
     * oversized page, a non-positive default, or a blank search language.
     */
    public static class Items {
        static final int DEFAULT_PAGE_SIZE = 20;
        static final int MAX_PAGE_SIZE = 100;
        static final String DEFAULT_SEARCH_LANGUAGE = "english";

        private int defaultPageSize = DEFAULT_PAGE_SIZE;
        private int maxPageSize = MAX_PAGE_SIZE;
        private String searchLanguage = DEFAULT_SEARCH_LANGUAGE;

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize <= 0 ? DEFAULT_PAGE_SIZE : defaultPageSize;
            if (this.defaultPageSize > this.maxPageSize) {
                this.defaultPageSize = this.maxPageSize;
            }
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize <= 0 ? MAX_PAGE_SIZE : maxPageSize;
            if (this.defaultPageSize > this.maxPageSize) {
                this.defaultPageSize = this.maxPageSize;
            }
        }

        public String getSearchLanguage() {
            return searchLanguage;
        }

        public void setSearchLanguage(String searchLanguage) {
            if (searchLanguage == null || searchLanguage.isBlank()) {
                this.searchLanguage = DEFAULT_SEARCH_LANGUAGE;
                return;
            }
            this.searchLanguage = searchLanguage.trim();
        }
    }

    public Cluster getCluster() {
        return cluster == null ? new Cluster() : cluster;
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster == null ? new Cluster() : cluster;
    }

    /**
     * Story clustering configuration.
     */
    public static class Cluster {
        private boolean enabled = true;
        private int windowHours = 48;
        private double titleThreshold = 0.60;
        private double wireThreshold = 0.40;
        private long passDelayMs = 300000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWindowHours() {
            return windowHours <= 0 ? 48 : windowHours;
        }

        public void setWindowHours(int windowHours) {
            this.windowHours = windowHours <= 0 ? 48 : windowHours;
        }

        public double getTitleThreshold() {
            return titleThreshold;
        }

        public void setTitleThreshold(double titleThreshold) {
            this.titleThreshold = titleThreshold;
        }

        public double getWireThreshold() {
            return wireThreshold;
        }

        public void setWireThreshold(double wireThreshold) {
            this.wireThreshold = wireThreshold;
        }

        public long getPassDelayMs() {
            return passDelayMs <= 0 ? 300000 : passDelayMs;
        }

        public void setPassDelayMs(long passDelayMs) {
            this.passDelayMs = passDelayMs <= 0 ? 300000 : passDelayMs;
        }
    }

    /**
     * Configuration for a single feed source.
     */
    public static class Source {
        private String name;
        private String url;
        private boolean enabled = true;
        private String cadenceClass = "daily";
        private String cadence;
        private String frequencyClass;
        private List<String> tags;

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

        public String getCadenceClass() {
            if (cadenceClass.isBlank()) {
                if (cadence != null && !cadence.isBlank()) {
                    return cadence;
                }
                if (frequencyClass != null && !frequencyClass.isBlank()) {
                    return frequencyClass;
                }
                return "daily";
            }
            return cadenceClass;
        }

        public void setCadenceClass(String cadenceClass) {
            this.cadenceClass = cadenceClass;
        }

        public String getCadence() {
            return getCadenceClass();
        }

        public void setCadence(String cadence) {
            this.cadence = cadence;
            if (cadence != null && !cadence.isBlank()) {
                this.cadenceClass = cadence;
            }
        }

        public String getFrequencyClass() {
            return getCadenceClass();
        }

        public void setFrequencyClass(String frequencyClass) {
            this.frequencyClass = frequencyClass;
            if (frequencyClass != null && !frequencyClass.isBlank()) {
                this.cadenceClass = frequencyClass;
            }
        }

        public List<String> getTags() {
            if (tags == null) {
                return null;
            }
            return java.util.Collections.unmodifiableList(tags);
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? null : new java.util.ArrayList<>(tags);
        }
    }
}
