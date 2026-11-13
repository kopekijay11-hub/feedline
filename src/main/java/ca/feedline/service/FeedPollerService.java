package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;

/**
 * One configured feed, polled on a fixed delay.
 *
 * <p>The cycle is: fetch -> validate -> parse with Rome -> normalize -> save with
 * insert-if-absent semantics. Insert-if-absent is enforced by the unique
 * (source_id, guid) constraint of the items table: a second save of the same
 * (source, guid) is rejected with a {@link org.springframework.dao.DataIntegrityViolationException}
 * and the first row keeps its identity. The poller deliberately does not catch
 * that exception per item: the constraint is the single source of truth, and a
 * duplicate item during a normal 60 second poll cycle would only roll back the
 * items saved in the same transaction.
 *
 * <p>To keep the unit-tests deterministic, the poller inserts the feed registry
 * row itself (feed_sources.url is unique), so the DB starts empty on first run.
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
     * <p>The fetch knows about the primary/fallback pair (CBS then BBC) and only
     * returns data that is valid RSS.
     */
    @Scheduled(fixedDelayString = "${feed.poll-delay:60s}")
    @Transactional
    public void pollOnce() {
        long started = System.currentTimeMillis();
        try {
            FeedFetchResult result = fetcher.fetch(config.getPrimaryFeedUrl(), config.getFallbackFeedUrl());
            if (!result.ok()) {
                log.warn("No valid RSS from {} or {} - skipping this poll cycle",
                        config.getPrimaryFeedUrl(), config.getFallbackFeedUrl());
                return;
            }

            FeedSource source = feedSources.findByUrl(result.usedUrl())
                    .orElseGet(() -> feedSources.save(new FeedSource("Auto-seeded feed", result.usedUrl())));

            Instant fetchedAt = Instant.now();
            int saved = 0;
            for (SyndEntry entry : parse(result.content())) {
                items.save(normalizer.normalize(entry, source, fetchedAt));
                saved++;
            }
            log.info("Polled {} (source id {}) -> {} items saved at {}", result.usedUrl(), source.getId(), saved, fetchedAt);
        } catch (Exception ex) {
            log.error("Feed poll cycle failed for {} - continuing", config.getPrimaryFeedUrl(), ex);
        } finally {
            log.debug("Poll cycle took {} ms", System.currentTimeMillis() - started);
        }
    }

    private List<SyndEntry> parse(String content) {
        try {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new StringReader(content));
            List<SyndEntry> entries = feed.getEntries();
            return entries == null ? List.of() : entries;
        } catch (FeedException e) {
            log.error("Failed to parse feed content", e);
            return List.of();
        }
    }
}
