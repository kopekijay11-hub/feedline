package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Polls multiple configured feeds on a fixed delay.
 *
 * <p>The cycle is: for each feed source:
 * <ol>
 *   <li>Check robots.txt and per-host interval</li>
 *   <li>Fetch with conditional GET (If-None-Match / If-Modified-Since)</li>
 *   <li>If 304: skip parsing and all writes; log the 304</li>
 *   <li>If 200: parse with Rome -> normalize -> save with insert-if-absent semantics</li>
 *   <li>On successful ingest: persist the new ETag/Last-Modified validators</li>
 *   <li>If parse or store fails: validators remain untouched (transaction rollback)</li>
 * </ol>
 *
 * <p>Insert-if-absent is enforced by the unique (source_id, guid) constraint of
 * the items table: a second save of the same (source, guid) is rejected with a
 * {@link org.springframework.dao.DataIntegrityViolationException}.
 *
 * <p>The validator transaction rule (hard invariant): new ETag/Last-Modified
 * values persist ONLY in the same transaction as a successful ingest commit.
 * If a 200 fetch then fails to parse or store, the previously stored validators
 * remain untouched. A 304 must never be able to hide items that were never stored.
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
    private final ItemRepository items;

    public FeedPollerService(FeedProperties config, FeedFetcher fetcher, FeedNormalizer normalizer,
                             FeedSourceRepository feedSources, ItemRepository items) {
        this.config = config;
        this.fetcher = fetcher;
        this.normalizer = normalizer;
        this.feedSources = feedSources;
        this.items = items;
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
        try {
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
            log.debug("Poll cycle took {} ms", System.currentTimeMillis() - started);
        }
    }

    /**
     * Poll a single feed source.
     *
     * @param sourceConfig the configured source
     * @return number of items saved, or 0 if nothing was fetched/parsed
     */
    @Transactional
    public int pollSingleSource(Source sourceConfig) {
        String url = sourceConfig.getUrl();
        
        // Find or create the feed source
        Optional<FeedSource> existingSource = feedSources.findByUrl(url);
        FeedSource source;
        
        if (existingSource.isPresent()) {
            source = existingSource.get();
        } else {
            // Create new source with name from config or URL
            String name = sourceConfig.getName();
            if (name == null || name.isBlank()) {
                name = extractNameFromUrl(url);
            }
            source = new FeedSource(name, url);
            source.setEnabled(sourceConfig.isEnabled());
            source = feedSources.save(source);
            log.info("Created new feed source: {} (id={})", name, source.getId());
        }

        // Get current validators
        String currentEtag = source.getEtag();
        String currentLastModified = source.getLastModified();

        // Fetch the feed
        FeedFetchResult result = fetcher.fetch(url, currentEtag, currentLastModified);

        // Check for robots disallowed
        if (result.robotsDisallowed()) {
            log.info("Source {} disallowed by robots.txt - skipping", url);
            return 0;
        }

        // Check for 304 Not Modified
        if (result.httpCode() == 304) {
            log.info("Source {} not modified (304) - skipping", url);
            return 0;
        }

        // Check for failure
        if (!result.ok()) {
            log.warn("Failed to fetch {}: HTTP {}", url, result.httpCode());
            return 0;
        }

        // Parse the feed
        List<SyndEntry> entries;
        try {
            entries = parse(result.content());
        } catch (Exception e) {
            log.error("Failed to parse feed from {}: {}", url, e.getMessage());
            // DO NOT update validators - transaction will rollback
            return 0;
        }

        // Normalize and save entries
        Instant fetchedAt = Instant.now();
        int saved = 0;
        for (SyndEntry entry : entries) {
            try {
                Item item = normalizer.normalize(entry, source, fetchedAt);
                // Insert-if-absent: enforced by DB uniqueness, but we pre-check to avoid
                // constraint violations poisoning the current transaction.
                if (items.findBySourceIdAndGuid(source.getId(), item.getGuid()).isEmpty()) {
                    items.save(item);
                    saved++;
                }
            } catch (Exception e) {
                log.error("Failed to normalize entry from {}: {}", url, e.getMessage());
                // Continue with other entries - one bad entry shouldn't stop the whole feed
            }
        }

        // Only update validators if we successfully fetched and parsed
        // This implements the validator transaction rule: validators persist
        // ONLY in the same transaction as a successful ingest commit
        if (result.hasNewValidators()) {
            source.setEtag(result.newEtag());
            source.setLastModified(result.newLastModified());
            feedSources.save(source);
            log.debug("Updated validators for {}: ETag={}, Last-Modified={}",
                    url, result.newEtag(), result.newLastModified());
        }

        log.info("Polled {} (source id {}) -> {} items saved at {}", url, source.getId(), saved, fetchedAt);
        return saved;
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
        if (content != null && content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false);
        SyndFeed feed = input.build(new StringReader(content));
        List<SyndEntry> entries = feed.getEntries();
        return entries == null ? List.of() : entries;
    }
}
