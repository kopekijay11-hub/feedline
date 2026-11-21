package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;

/**
 * Polls multiple configured feeds on a fixed delay.
 *
 * <p>The cycle is: for each feed source:
 * <ol>
 *   <li>Check robots.txt and per-host interval</li>
 *   <li>Fetch with conditional GET (If-None-Match / If-Modified-Since)</li>
 *   <li>If 304: skip parsing and all writes; log the 304</li>
 *   <li>If 200: parse with Rome, then ingest each item in its own REQUIRES_NEW transaction</li>
 *   <li>On a clean ingest: persist the new ETag/Last-Modified validators</li>
 *   <li>If any item is skipped: validators remain untouched and the skip is recorded</li>
 * </ol>
 *
 * <p>Insert-if-absent is enforced by the unique (source_id, guid) constraint of
 * the items table: a second save of the same (source, guid) is rejected with a
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 *
 * <p>The validator rule (hard invariant): new ETag/Last-Modified values persist
 * only after a fully clean 200 poll. If any item is skipped, the previously
 * stored validators remain untouched. A 304 must never hide items that were
 * never stored.
 *
 * <p>Per-host politeness: minimum interval between requests to the same origin
 * is enforced via an injectable clock and sleeper seam.
 */
@Service
public class FeedPollerService {

    private static final Logger log = LoggerFactory.getLogger(FeedPollerService.class);

    private final FeedProperties config;
    private final FeedFetcher fetcher;
    private final FeedNormalizer normalizer;
    private final FeedSourceRepository feedSources;
    private final ItemIngestService itemIngest;
    private final IngestSkipRecorder skipRecorder;
    private FeedlineMetrics metrics;

    private volatile Instant lastCompletedPollCycle = null;

    @Autowired
    public FeedPollerService(FeedProperties config, FeedFetcher fetcher, FeedNormalizer normalizer,
                             FeedSourceRepository feedSources, ItemIngestService itemIngest,
                             IngestSkipRecorder skipRecorder) {
        this(config, fetcher, normalizer, feedSources, itemIngest, skipRecorder, null);
    }

    public FeedPollerService(FeedProperties config, FeedFetcher fetcher, FeedNormalizer normalizer,
                             FeedSourceRepository feedSources, ItemIngestService itemIngest,
                             IngestSkipRecorder skipRecorder, FeedlineMetrics metrics) {
        this.config = config;
        this.fetcher = fetcher;
        this.normalizer = normalizer;
        this.feedSources = feedSources;
        this.itemIngest = itemIngest;
        this.skipRecorder = skipRecorder;
        this.metrics = metrics;
    }

    /**
     * Time at which the most recent full poll cycle completed. {@code null} until
     * the first cycle has finished (its own state, not "just now").
     */
    @Autowired(required = false)
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injection")
    public void setMetrics(FeedlineMetrics metrics) {
        if (metrics != null) {
            this.metrics = metrics;
        }
    }

    public Instant getLastCompletedPollCycle() {
        return lastCompletedPollCycle;
    }

    /**
     * Pull one cycle. Runs forever on a fixed delay that is configurable via
     * {@code feed.poll-delay} (a {@link java.time.Duration} string, e.g. "60s").
     *
     * <p>Fetches all configured feed sources, with polite behavior between sources
     * on the same origin.
     */
    @Scheduled(fixedDelayString = "${feed.poll-delay:60s}")
    public void pollOnce() {
        long started = System.currentTimeMillis();
        boolean cycleRan = false;
        try {
            skipRecorder.prune(config.getSkipRetention());

            // Get all configured sources
            List<Source> configuredSources = config.getSources();
            if (configuredSources == null || configuredSources.isEmpty()) {
                log.warn("No feed sources configured - skipping poll");
                return;
            }

            int totalSaved = 0;
            int totalSourcesProcessed = 0;

            for (Source sourceConfig : configuredSources) {
                if (!sourceConfig.isEnabled()) {
                    log.debug("Skipping disabled source: {}", sourceConfig.getUrl());
                    continue;
                }

                try {
                    cycleRan = true;
                    int saved = pollSingleSource(sourceConfig);
                    totalSaved += saved;
                    totalSourcesProcessed++;
                } catch (Exception ex) {
                    log.error("Error polling source {}: {}", sourceConfig.getUrl(), ex.getMessage());
                    // Continue with other sources - one failure should not stop all
                }
            }

            log.info("Poll cycle completed: {} sources processed, {} items saved, took {} ms",
                    totalSourcesProcessed, totalSaved, System.currentTimeMillis() - started);
        } catch (Exception ex) {
            log.error("Feed poll cycle failed - continuing", ex);
        } finally {
            if (cycleRan) {
                lastCompletedPollCycle = Instant.now();
            }
            log.debug("Poll cycle took {} ms", System.currentTimeMillis() - started);
        }
    }

    /**
     * Poll a single feed source.
     *
     * @param sourceConfig the configured source
     * @return number of items saved, or 0 if nothing was fetched/parsed
     */
    public int pollSingleSource(Source sourceConfig) {
        String url = sourceConfig.getUrl();
        Instant attemptAt = Instant.now();

        Optional<FeedSource> existingSource = null;
        try {
            existingSource = feedSources.findByUrl(url);
        } catch (Exception ignored) {
            existingSource = Optional.empty();
        }
        FeedSource source;
        if (existingSource != null && existingSource.isPresent()) {
            source = existingSource.get();
        } else {
            String name = sourceConfig.getName();
            if (name == null || name.isBlank()) {
                name = extractNameFromUrl(url);
            }
            source = new FeedSource(name, url);
            source.setEnabled(sourceConfig.isEnabled());
            try {
                FeedSource saved = feedSources.save(source);
                if (saved != null) {
                    source = saved;
                }
                log.info("Created new feed source: {} (id={})", name, source.getId());
            } catch (Exception e) {
                try {
                    Optional<FeedSource> found = feedSources.findByUrl(url);
                    if (found != null && found.isPresent()) {
                        source = found.get();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        source.setLastAttemptAt(attemptAt);

        String currentEtag = source.getEtag();
        String currentLastModified = source.getLastModified();

        try {
            FeedFetchResult result = fetcher.fetch(url, currentEtag, currentLastModified);

            if (result.robotsDisallowed()) {
                log.info("Source {} disallowed by robots.txt - skipping", url);
                source.setLastErrorClass("ROBOTS");
                source.setConsecutiveFailures(source.getConsecutiveFailures() + 1);
                feedSources.save(source);
                return 0;
            }

            if (result.httpCode() == 304) {
                log.info("Source {} not modified (304) - skipping", url);
                source.setLastSuccessAt(Instant.now());
                source.setLastErrorClass(null);
                source.setConsecutiveFailures(0);
                feedSources.save(source);
                return 0;
            }

            if (!result.ok()) {
                log.warn("Failed to fetch {}: HTTP {}", url, result.httpCode());
                String errorClass = result.httpCode() == 0 ? "NETWORK" : "HTTP_" + result.httpCode();
                source.setLastErrorClass(errorClass);
                source.setConsecutiveFailures(source.getConsecutiveFailures() + 1);
                feedSources.save(source);
                return 0;
            }

            List<SyndEntry> entries;
            try {
                entries = parse(result.content());
            } catch (Exception e) {
                log.error("Failed to parse feed from {}: {}", url, e.getMessage());
                source.setLastErrorClass("PARSE");
                source.setConsecutiveFailures(source.getConsecutiveFailures() + 1);
                feedSources.save(source);
                return 0;
            }

            Instant fetchedAt = Instant.now();
            int saved = 0;
            int skipped = 0;
            for (SyndEntry entry : entries) {
                try {
                    ItemIngestResult ingested = itemIngest.ingest(source, entry, fetchedAt);
                    if (ingested.stored()) {
                        saved++;
                    }
                } catch (ItemIngestFailure failure) {
                    skipped++;
                    if (metrics != null) {
                        metrics.recordIngestSkipped();
                    }
                    recordSkipSafely(source.getId(), failure.getGuid(), failure.getFailureClass(), url);
                    log.warn("Skipped item {} from {}: {}", failure.getGuid(), url, failure.getFailureClass());
                } catch (RuntimeException e) {
                    skipped++;
                    if (metrics != null) {
                        metrics.recordIngestSkipped();
                    }
                    String guid;
                    try {
                        guid = normalizer.guidFor(entry);
                    } catch (RuntimeException ignored) {
                        guid = "UNKNOWN";
                    }
                    recordSkipSafely(source.getId(), guid, "INGEST_ERROR", url);
                    log.error("Skipped item {} from {}: {}", guid, url, e.getMessage());
                }
            }

            if (skipped == 0) {
                source.setEtag(result.newEtag());
                source.setLastModified(result.newLastModified());
                source = feedSources.save(source);
            }

            if (saved > 0) {
                source.setLastNewItemAt(fetchedAt);
            }

            source.setLastSuccessAt(fetchedAt);
            source.setLastErrorClass(null);
            source.setConsecutiveFailures(0);
            source = feedSources.save(source);

            log.info("Polled {} (source id {}) -> {} items saved at {}", url, source.getId(), saved, fetchedAt);
            return saved;
        } catch (Exception ex) {
            log.error("Error polling source {}: {}", url, ex.getMessage());
            source.setLastErrorClass("NETWORK");
            source.setConsecutiveFailures(source.getConsecutiveFailures() + 1);
            feedSources.save(source);
            return 0;
        }
    }

    private void recordSkipSafely(Long sourceId, String guid, String failureClass, String url) {
        try {
            skipRecorder.record(sourceId, guid, failureClass, Instant.now());
        } catch (RuntimeException e) {
            log.error("Failed to record skip for item {} from {}: {}", guid, url, e.getMessage());
        }
    }

    /**
     * Extract a name from a URL for use as a feed source name.
     */
    private String extractNameFromUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            
            if (host != null && path != null && !path.isBlank()) {
                // Extract last part of path
                String[] parts = path.split("/");
                String lastPart = parts[parts.length - 1];
                if (!lastPart.isBlank()) {
                    return host + " - " + lastPart;
                }
                return host;
            }
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    /**
     * Parse feed content with Rome.
     *
     * <p>Parsing is hardened against XXE/entity expansion by disallowing DOCTYPE
     * declarations, so untrusted feed content cannot define or resolve external
     * entities.
     *
     * @param content the feed XML content
     * @return list of entries, or empty list if parsing fails
     */
    List<SyndEntry> parse(String content) throws Exception {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false);
        SyndFeed feed = input.build(new StringReader(content));
        List<SyndEntry> entries = feed.getEntries();
        return entries == null ? List.of() : entries;
    }
}
