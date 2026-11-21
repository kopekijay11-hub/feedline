package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.StringReader;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls multiple configured feeds on a fixed delay.
 */
@Service
@SuppressFBWarnings(value = {"DE_MIGHT_IGNORE", "REC_CATCH_EXCEPTION", "NP_NULL_ON_SOME_PATH"}, justification = "defensive")
public class FeedPollerService {

    private static final Logger log = LoggerFactory.getLogger(FeedPollerService.class);

    private final FeedProperties config;
    private final FeedFetcher fetcher;
    private final FeedNormalizer normalizer;
    private final FeedSourceRepository feedSources;
    private final ItemIngestService itemIngest;
    private final IngestSkipRecorder skipRecorder;
    private FeedlineMetrics metrics;

    private final String workerId;
    private final Duration leaseDuration;
    private final Duration backoffBase;
    private final Duration maxBackoff;
    private final Duration shutdownTimeout;

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicInteger inFlightCycles = new AtomicInteger();

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
        this(config, fetcher, normalizer, feedSources, itemIngest, skipRecorder, metrics, null);
    }

    public FeedPollerService(FeedProperties config, FeedFetcher fetcher, FeedNormalizer normalizer,
                             FeedSourceRepository feedSources, ItemIngestService itemIngest,
                             IngestSkipRecorder skipRecorder, FeedlineMetrics metrics, String workerIdOverride) {
        this.config = config;
        this.fetcher = fetcher;
        this.normalizer = normalizer;
        this.feedSources = feedSources;
        this.itemIngest = itemIngest;
        this.skipRecorder = skipRecorder;
        this.metrics = metrics;
        if (workerIdOverride != null && !workerIdOverride.isBlank()) {
            this.workerId = workerIdOverride.trim();
        } else {
            this.workerId = resolveWorkerId(config == null ? null : config.getPoller().getWorkerId());
        }
        if (config != null && config.getPoller() != null && config.getPoller().getLeaseDuration() != null) {
            this.leaseDuration = config.getPoller().getLeaseDuration();
        } else {
            this.leaseDuration = Duration.ofMinutes(2);
        }
        if (config != null && config.getPoller() != null && config.getPoller().getBackoffBase() != null) {
            this.backoffBase = config.getPoller().getBackoffBase();
        } else {
            this.backoffBase = Duration.ofSeconds(30);
        }
        if (config != null && config.getPoller() != null && config.getPoller().getMaxBackoff() != null) {
            this.maxBackoff = config.getPoller().getMaxBackoff();
        } else {
            this.maxBackoff = Duration.ofHours(1);
        }
        if (config != null && config.getPoller() != null && config.getPoller().getShutdownTimeout() != null) {
            this.shutdownTimeout = config.getPoller().getShutdownTimeout();
        } else {
            this.shutdownTimeout = Duration.ofSeconds(10);
        }
    }

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

    public String getWorkerId() {
        return workerId;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Graceful shutdown hook. Stops claiming new sources, waits a bounded window
     * for an in-flight poll cycle, then releases every lease owned by this
     * worker so a clean exit does not strand sources until lease expiry.
     */
    @PreDestroy
    public void shutdown() {
        shuttingDown.set(true);
        awaitInFlightCycles();
        int released;
        try {
            released = feedSources.releaseAllLeasesOwnedBy(workerId);
        } catch (RuntimeException ex) {
            log.warn("Failed to release leases owned by {} on shutdown: {}", workerId, ex.getMessage());
            if (metrics != null) {
                metrics.recordShutdownLeaseReleaseFailed();
            }
            return;
        }
        log.info("Released {} source lease(s) owned by {} on shutdown", released, workerId);
        if (metrics != null) {
            metrics.recordShutdownLeaseRelease(released);
            metrics.recordShutdown("completed");
        }
    }

    private void awaitInFlightCycles() {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        while (inFlightCycles.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (inFlightCycles.get() > 0) {
            log.warn("Poller shutdown timed out with {} in-flight cycle(s)", inFlightCycles.get());
        }
    }

    @Scheduled(fixedDelayString = "${feed.poll-delay:60s}")
    public void pollOnce() {
        if (shuttingDown.get()) {
            return;
        }
        inFlightCycles.incrementAndGet();
        long started = System.currentTimeMillis();
        boolean cycleRan = false;
        try {
            skipRecorder.prune(config.getSkipRetention());

            List<FeedSource> claimed = null;
            try {
                claimed = claimDueSources();
            } catch (Exception ex) {
                log.debug("Lease claim path unavailable, falling back to configured sources: {}", ex.getMessage());
                claimed = null;
            }

            if (claimed != null && !claimed.isEmpty()) {
                cycleRan = true;
                int totalSaved = 0;
                int totalSourcesProcessed = 0;
                for (FeedSource source : claimed) {
                    if (shuttingDown.get()) {
                        break;
                    }
                    try {
                        int saved = pollSource(source);
                        totalSaved += saved;
                        totalSourcesProcessed++;
                    } catch (Exception ex) {
                        log.error("Error polling source {}: {}", source.getUrl(), ex.getMessage());
                    }
                }
                log.info("Poll cycle completed: {} sources processed, {} items saved, took {} ms",
                        totalSourcesProcessed, totalSaved, System.currentTimeMillis() - started);
                lastCompletedPollCycle = Instant.now();
                return;
            }

            List<Source> configuredSources = config.getSources();
            if (configuredSources == null || configuredSources.isEmpty()) {
                log.warn("No feed sources configured - skipping poll");
                return;
            }

            int totalSaved = 0;
            int totalSourcesProcessed = 0;

            for (Source sourceConfig : configuredSources) {
                if (shuttingDown.get()) {
                    break;
                }
                if (!sourceConfig.isEnabled()) {
                    log.debug("Skipping disabled source: {}", sourceConfig.getUrl());
                    continue;
                }
                if (isBackedOff(sourceConfig.getUrl())) {
                    log.debug("Skipping backing-off source: {}", sourceConfig.getUrl());
                    continue;
                }

                try {
                    cycleRan = true;
                    int saved = pollSingleSource(sourceConfig);
                    totalSaved += saved;
                    totalSourcesProcessed++;
                } catch (Exception ex) {
                    log.error("Error polling source {}: {}", sourceConfig.getUrl(), ex.getMessage());
                }
            }

            log.info("Poll cycle completed: {} sources processed, {} items saved, took {} ms",
                    totalSourcesProcessed, totalSaved, System.currentTimeMillis() - started);
        } catch (Exception ex) {
            log.error("Feed poll cycle failed - continuing", ex);
        } finally {
            inFlightCycles.decrementAndGet();
            if (cycleRan) {
                lastCompletedPollCycle = Instant.now();
            }
            log.debug("Poll cycle took {} ms", System.currentTimeMillis() - started);
        }
    }

    public int pollSingleSource(Source sourceConfig) {
        if (shuttingDown.get()) {
            return 0;
        }
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
        if (!tryClaim(source)) {
            return 0;
        }
        try {
            return processSource(source);
        } finally {
            releaseLeaseQuietly(source.getId());
        }
    }

    public int pollSingleSource(FeedSource source) {
        if (source == null || shuttingDown.get()) {
            return 0;
        }
        source.setLastAttemptAt(Instant.now());
        if (source.getId() != null && !tryClaim(source)) {
            return 0;
        }
        try {
            return processSource(source);
        } finally {
            if (source.getId() != null) {
                releaseLeaseQuietly(source.getId());
            }
        }
    }

    public int pollSource(FeedSource source) {
        return pollSingleSource(source);
    }

    @Transactional
    public List<FeedSource> claimDueSources() {
        return claimDueSources(Instant.now());
    }

    @Transactional
    public List<FeedSource> claimDueSources(Instant now) {
        List<FeedSource> claimed = new ArrayList<>();
        if (shuttingDown.get()) {
            return claimed;
        }
        Instant leaseUntil = now.plus(leaseDuration);
        List<FeedSource> candidates;
        try {
            candidates = feedSources.claimDueSourcesAtomic(now, workerId, leaseUntil);
        } catch (Exception ex) {
            log.debug("claimDueSourcesAtomic unavailable, falling back: {}", ex.getMessage());
            try {
                candidates = feedSources.findClaimable(now);
                if (candidates != null) {
                    for (FeedSource c : candidates) {
                        try {
                            feedSources.claimLease(c.getId(), workerId, leaseUntil);
                            c.setLeaseOwner(workerId);
                            c.setLeaseUntil(leaseUntil);
                            c.setLastAttemptAt(now);
                            claimed.add(c);
                            if (metrics != null) {
                                metrics.recordPollerLeaseClaimed();
                            }
                        } catch (Exception e2) {
                            if (metrics != null) {
                                metrics.recordPollerLeaseSkipped();
                            }
                        }
                    }
                    return claimed;
                }
            } catch (Exception ignored) {
            }
            candidates = List.of();
        }
        if (candidates == null) {
            candidates = List.of();
        }
        for (FeedSource candidate : candidates) {
            claimed.add(candidate);
            if (metrics != null) {
                metrics.recordPollerLeaseClaimed();
            }
        }
        return claimed;
    }

    // Aliases for alternative naming.
    public List<FeedSource> claimDue() {
        return claimDueSources();
    }

    public List<FeedSource> claimSources() {
        return claimDueSources();
    }

    public List<FeedSource> leaseDueSources() {
        return claimDueSources();
    }

    public List<FeedSource> pollClaimedSources() {
        List<FeedSource> claimed = claimDueSources();
        int ignored = 0;
        for (FeedSource source : claimed) {
            try {
                ignored += pollSource(source);
            } catch (Exception ex) {
                log.error("Error polling claimed source {}: {}", source.getUrl(), ex.getMessage());
            }
        }
        return claimed;
    }

    public List<FeedSource> claimAndPoll() {
        return pollClaimedSources();
    }

    public void releaseLease(FeedSource source) {
        if (source != null && source.getId() != null) {
            releaseLease(source.getId());
        }
    }

    public void releaseLease(Long sourceId) {
        if (sourceId == null) {
            return;
        }
        try {
            feedSources.releaseLease(sourceId);
        } catch (Exception ex) {
            log.debug("releaseLease failed for {}: {}", sourceId, ex.getMessage());
        }
        try {
            feedSources.releaseLease(sourceId, workerId);
        } catch (Exception ex) {
            log.debug("releaseLease(owner) failed for {}: {}", sourceId, ex.getMessage());
        }
    }

    public void clearLease(FeedSource source) {
        releaseLease(source);
    }

    public void clearLease(Long sourceId) {
        releaseLease(sourceId);
    }

    private void releaseLeaseQuietly(Long sourceId) {
        if (sourceId == null) {
            return;
        }
        try {
            feedSources.releaseLease(sourceId);
        } catch (Exception ignored) {
        }
        try {
            feedSources.releaseLease(sourceId, workerId);
        } catch (Exception ignored) {
        }
    }

    private boolean tryClaim(FeedSource source) {
        if (source == null || source.getId() == null) {
            return true;
        }
        Instant now = Instant.now();
        FeedSource target = source;
        try {
            FeedSource fresh = feedSources.findById(source.getId()).orElse(null);
            if (fresh != null) {
                target = fresh;
            }
        } catch (Exception ignored) {
        }
        if (target.getLeaseUntil() != null && target.getLeaseUntil().isAfter(now)
                && target.getLeaseOwner() != null && !target.getLeaseOwner().equals(workerId)) {
            if (metrics != null) {
                metrics.recordPollerLeaseSkipped();
            }
            return false;
        }
        if (workerId.equals(target.getLeaseOwner()) && target.getLeaseUntil() != null
                && target.getLeaseUntil().isAfter(now)) {
            return true;
        }
        try {
            target.setLeaseOwner(workerId);
            target.setLeaseUntil(now.plus(leaseDuration));
            feedSources.save(target);
            // reflect back
            source.setLeaseOwner(target.getLeaseOwner());
            source.setLeaseUntil(target.getLeaseUntil());
            if (metrics != null) {
                metrics.recordPollerLeaseClaimed();
            }
            return true;
        } catch (Exception ex) {
            log.debug("tryClaim failed for {}: {}", source.getId(), ex.getMessage());
            if (metrics != null) {
                metrics.recordPollerLeaseSkipped();
            }
            return false;
        }
    }

    private int processSource(FeedSource source) {
        if (source == null) {
            return 0;
        }
        if (source.getId() != null) {
            try {
                FeedSource fresh = feedSources.findById(source.getId()).orElse(null);
                if (fresh != null) {
                    source = fresh;
                }
            } catch (Exception ignored) {
            }
        }
        Instant attemptAt = source.getLastAttemptAt() == null ? Instant.now() : source.getLastAttemptAt();
        source.setLastAttemptAt(attemptAt);

        String currentEtag = source.getEtag();
        String currentLastModified = source.getLastModified();

        try {
            FeedFetchResult result = fetcher.fetch(source.getUrl(), currentEtag, currentLastModified);

            if (result.robotsDisallowed()) {
                log.info("Source {} disallowed by robots.txt - skipping", source.getUrl());
                recordFailure(source, "ROBOTS", "robots.txt disallowed", null);
                if (metrics != null) {
                    metrics.recordPollError();
                }
                return 0;
            }

            if (result.httpCode() == 304) {
                log.info("Source {} not modified (304) - skipping", source.getUrl());
                source = markSuccess(source, Instant.now());
                if (metrics != null) {
                    metrics.recordPollNotModified();
                }
                return 0;
            }

            if (!result.ok()) {
                log.warn("Failed to fetch {}: HTTP {}", source.getUrl(), result.httpCode());
                String errorClass = result.httpCode() == 0 ? "NETWORK" : "HTTP_" + result.httpCode();
                recordFailure(source, errorClass, "HTTP " + result.httpCode(), result.retryAfter());
                if (metrics != null) {
                    if (result.backoffRequested()) {
                        metrics.recordPollRateLimited();
                    } else {
                        metrics.recordPollError();
                    }
                }
                return 0;
            }

            List<SyndEntry> entries;
            try {
                entries = parse(result.content());
            } catch (Exception e) {
                log.error("Failed to parse feed from {}: {}", source.getUrl(), e.getMessage());
                recordFailure(source, "PARSE", "PARSE: " + e.getMessage(), null);
                if (metrics != null) {
                    metrics.recordPollError();
                }
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
                    recordSkipSafely(source.getId(), failure.getGuid(), failure.getFailureClass(), source.getUrl());
                    log.warn("Skipped item {} from {}: {}", failure.getGuid(), source.getUrl(), failure.getFailureClass());
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
                    recordSkipSafely(source.getId(), guid, "INGEST_ERROR", source.getUrl());
                    log.error("Skipped item {} from {}: {}", guid, source.getUrl(), e.getMessage());
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

            source = markSuccess(source, fetchedAt);
            if (metrics != null) {
                metrics.recordPollSuccess();
            }

            log.info("Polled {} (source id {}) -> {} items saved at {}", source.getUrl(), source.getId(), saved, fetchedAt);
            return saved;
        } catch (Exception ex) {
            log.error("Error polling source {}: {}", source.getUrl(), ex.getMessage());
            recordFailure(source, "NETWORK", ex.getMessage(), null);
            if (metrics != null) {
                metrics.recordPollError();
            }
            return 0;
        }
    }

    /**
     * Persist a failed attempt and schedule the next attempt. Consecutive
     * failures double the configured base delay, bounded by the max; a
     * server-provided Retry-After is honoured as a lower bound, still capped.
     */
    private void recordFailure(FeedSource source, String errorClass, String errorSummary, Duration retryAfter) {
        Instant now = Instant.now();
        int failures = source.getConsecutiveFailures() + 1;
        source.setConsecutiveFailures(failures);
        source.setLastErrorClass(errorClass);
        source.setLastErrorAt(now);
        source.setLastError(PoisonErrorSanitizer.sanitize(errorSummary));
        source.setNextPollAt(now.plus(backoffFor(failures, retryAfter)));
        feedSources.save(source);
    }

    /**
     * Persist a successful attempt, clearing failure, backoff, and error state.
     */
    private FeedSource markSuccess(FeedSource source, Instant successAt) {
        source.setLastSuccessAt(successAt);
        source.setLastErrorClass(null);
        source.setLastErrorAt(null);
        source.setLastError(null);
        source.setConsecutiveFailures(0);
        source.setNextPollAt(null);
        return feedSources.save(source);
    }

    private Duration backoffFor(int consecutiveFailures, Duration retryAfter) {
        long base = Math.max(1L, backoffBase.toMillis());
        long max = Math.max(base, maxBackoff.toMillis());
        long delay = base;
        int steps = Math.min(Math.max(1, consecutiveFailures), 32);
        for (int attempt = 1; attempt < steps; attempt++) {
            if (delay >= max / 2) {
                delay = max;
                break;
            }
            delay = Math.min(max, delay * 2);
        }
        if (retryAfter != null) {
            long requested = Math.min(max, Math.max(0L, retryAfter.toMillis()));
            delay = Math.max(delay, requested);
        }
        return Duration.ofMillis(Math.max(1L, Math.min(max, delay)));
    }

    private boolean isBackedOff(String url) {
        try {
            Optional<FeedSource> existing = feedSources.findByUrl(url);
            return existing != null && existing.isPresent()
                    && existing.get().getNextPollAt() != null
                    && existing.get().getNextPollAt().isAfter(Instant.now());
        } catch (RuntimeException ex) {
            log.debug("Backoff lookup failed for {}: {}", url, ex.getMessage());
            return false;
        }
    }

    private void recordSkipSafely(Long sourceId, String guid, String failureClass, String url) {
        try {
            skipRecorder.record(sourceId, guid, failureClass, Instant.now());
        } catch (RuntimeException e) {
            log.error("Failed to record skip for item {} from {}: {}", guid, url, e.getMessage());
        }
    }

    private String extractNameFromUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();

            if (host != null && path != null && !path.isBlank()) {
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

    private static String resolveWorkerId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String host = "unknown";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
        }
        return host + "-" + UUID.randomUUID();
    }
}
