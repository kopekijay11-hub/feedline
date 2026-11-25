package ca.feedline.health;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.service.FeedPollerService;
import ca.feedline.service.FeedlineMetrics;
import ca.feedline.service.PoisonErrorSanitizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SourceHealthService {

    private final FeedSourceRepository sources;
    private final IngestSkipRepository skips;
    private final FeedPollerService poller;
    private final FeedProperties config;
    private FeedlineMetrics metrics;

    public SourceHealthService(FeedSourceRepository sources, IngestSkipRepository skips,
                               FeedPollerService poller, FeedProperties config) {
        this.sources = sources;
        this.skips = skips;
        this.poller = poller;
        this.config = config;
    }

    @Autowired(required = false)
    public void setMetrics(FeedlineMetrics metrics) {
        if (metrics != null) {
            this.metrics = metrics;
        }
    }

    public boolean schedulingEnabled() {
        return config.isSchedulingEnabled();
    }

    public Duration pollInterval() {
        String raw = config.getPollDelay();
        if (raw == null || raw.isBlank()) {
            return Duration.ofSeconds(60);
        }
        String trimmed = raw.trim();
        if (trimmed.matches("\\d+")) {
            return Duration.ofMillis(Long.parseLong(trimmed));
        }
        try {
            return Duration.parse(trimmed);
        } catch (Exception e) {
            return Duration.ofSeconds(60);
        }
    }

    public Instant lastCompletedPollCycle() {
        return poller.getLastCompletedPollCycle();
    }

    public boolean heartbeatStale() {
        if (!config.isSchedulingEnabled()) {
            return false;
        }
        Instant heartbeat = lastCompletedPollCycle();
        return heartbeat == null
                || Duration.between(heartbeat, Instant.now()).compareTo(pollInterval().multipliedBy(2)) > 0;
    }

    public Duration contentFreshnessThreshold() {
        Duration threshold = config.getContentFreshnessThreshold();
        return threshold == null || threshold.isNegative() ? Duration.ofDays(7) : threshold;
    }

    public String contentFreshness(FeedSource source, Instant now) {
        Instant lastNewItem = source.getLastNewItemAt();
        if (lastNewItem == null) {
            return "UNKNOWN";
        }
        return Duration.between(lastNewItem, now).compareTo(contentFreshnessThreshold()) > 0
                ? "STALE" : "CURRENT";
    }

    public SourceHealthDetail healthFor(FeedSource source) {
        Instant now = Instant.now();
        Duration interval = pollInterval();
        Instant lastAttempt = source.getLastAttemptAt();
        Instant lastSuccess = source.getLastSuccessAt();
        String state;
        boolean backoff = false;
        if (!source.isEnabled()) {
            state = "DISABLED";
        } else if (lastAttempt == null) {
            state = "UNKNOWN";
        } else {
            boolean lastAttemptFailed = source.getLastErrorClass() != null;
            boolean stale = lastSuccess == null
                    || Duration.between(lastSuccess, now).compareTo(interval.multipliedBy(2)) > 0;
            boolean rateLimited = isBackoffClass(source.getLastErrorClass());
            backoff = rateLimited && source.getNextPollAt() != null && source.getNextPollAt().isAfter(now);
            if (backoff) {
                state = "BACKOFF";
            } else if (lastAttemptFailed || stale) {
                state = "DEGRADED";
            } else {
                state = "UP";
            }
        }
        Long secondsSince = (lastSuccess == null) ? null : Duration.between(lastSuccess, now).toSeconds();
        boolean validatorsPresent = source.getEtag() != null || source.getLastModified() != null;
        return new SourceHealthDetail(
                source.getId(),
                source.getName(),
                state,
                lastAttempt,
                lastSuccess,
                source.getLastErrorClass(),
                source.getLastErrorAt(),
                PoisonErrorSanitizer.sanitize(source.getLastError()),
                source.getNextPollAt(),
                backoff,
                source.getConsecutiveFailures(),
                validatorsPresent,
                secondsSince,
                source.getLastNewItemAt(),
                contentFreshness(source, now),
                interval.toSeconds(),
                skips.countBySourceId(source.getId()),
                skips.sumOccurrencesBySourceId(source.getId())
        );
    }

    private static boolean isBackoffClass(String errorClass) {
        return "HTTP_429".equals(errorClass)
                || "HTTP_503".equals(errorClass)
                || "RATE_LIMITED".equals(errorClass);
    }

    public SourcesHealth aggregate() {
        Duration interval = pollInterval();
        List<SourceHealthDetail> details = sources.findAll().stream()
                .map(this::healthFor).toList();
        Instant heartbeat = lastCompletedPollCycle();
        boolean heartbeatStale = heartbeatStale();
        boolean anyDegraded = details.stream()
                .anyMatch(d -> "DEGRADED".equals(d.state()) || "BACKOFF".equals(d.state()));
        String sourcesStatus = (!heartbeatStale && !anyDegraded) ? "UP" : "DEGRADED";
        updateMetrics(details);
        return new SourcesHealth(sourcesStatus, sourcesStatus, heartbeat, heartbeatStale,
                interval.toSeconds(), details);
    }

    public List<SourceScorecard> scorecards() {
        return sources.findAll().stream()
                .map(this::healthFor)
                .map(SourceScorecardCalculator::score)
                .sorted(java.util.Comparator.comparing(SourceScorecard::id, java.util.Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

    private void updateMetrics(List<SourceHealthDetail> details) {
        if (metrics == null) {
            return;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        long active = 0L;
        for (SourceHealthDetail detail : details) {
            String state = metricState(detail.state());
            counts.merge(state, 1L, Long::sum);
            if (!"DISABLED".equals(detail.state())) {
                active++;
            }
        }
        metrics.updateSourceHealth(counts, active);
    }

    private static String metricState(String state) {
        if (state == null) {
            return "unknown";
        }
        return switch (state) {
            case "UP" -> "healthy";
            case "DEGRADED" -> "degraded";
            case "BACKOFF" -> "backoff";
            case "DISABLED" -> "disabled";
            default -> "unknown";
        };
    }

    public Optional<SourceHealthDetail> findById(Long id) {
        return sources.findById(id).map(this::healthFor);
    }
}
