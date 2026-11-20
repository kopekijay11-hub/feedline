package ca.feedline.health;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.service.FeedPollerService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class SourceHealthService {

    private final FeedSourceRepository sources;
    private final IngestSkipRepository skips;
    private final FeedPollerService poller;
    private final FeedProperties config;

    public SourceHealthService(FeedSourceRepository sources, IngestSkipRepository skips,
                               FeedPollerService poller, FeedProperties config) {
        this.sources = sources;
        this.skips = skips;
        this.poller = poller;
        this.config = config;
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
        if (lastAttempt == null) {
            state = "UNKNOWN";
        } else {
            boolean lastAttemptFailed = source.getLastErrorClass() != null;
            boolean stale = lastSuccess == null
                    || Duration.between(lastSuccess, now).compareTo(interval.multipliedBy(2)) > 0;
            if (lastAttemptFailed || stale) {
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

    public SourcesHealth aggregate() {
        Duration interval = pollInterval();
        List<SourceHealthDetail> details = sources.findAll().stream()
                .map(this::healthFor).toList();
        Instant heartbeat = lastCompletedPollCycle();
        boolean heartbeatStale = heartbeatStale();
        boolean anyDegraded = details.stream().anyMatch(d -> "DEGRADED".equals(d.state()));
        String sourcesStatus = (!heartbeatStale && !anyDegraded) ? "UP" : "DEGRADED";
        return new SourcesHealth(sourcesStatus, sourcesStatus, heartbeat, heartbeatStale,
                interval.toSeconds(), details);
    }

    public Optional<SourceHealthDetail> findById(Long id) {
        return sources.findById(id).map(this::healthFor);
    }
}
