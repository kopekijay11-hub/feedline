package ca.feedline.health;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.service.FeedPollerService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class SourceHealthService {

    private final FeedSourceRepository sources;
    private final FeedPollerService poller;
    private final FeedProperties config;

    public SourceHealthService(FeedSourceRepository sources, FeedPollerService poller, FeedProperties config) {
        this.sources = sources;
        this.poller = poller;
        this.config = config;
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
                interval.toSeconds()
        );
    }

    public SourcesHealth aggregate() {
        Instant now = Instant.now();
        Duration interval = pollInterval();
        List<SourceHealthDetail> details = sources.findAll().stream()
                .map(this::healthFor).toList();
        Instant heartbeat = poller.getLastCompletedPollCycle();
        boolean heartbeatStale = heartbeat == null
                || Duration.between(heartbeat, now).compareTo(interval.multipliedBy(2)) > 0;
        boolean anyDegraded = details.stream().anyMatch(d -> "DEGRADED".equals(d.state()));
        boolean healthy = !heartbeatStale && !anyDegraded;
        String status = healthy ? "UP" : "DOWN";
        return new SourcesHealth(status, heartbeat, heartbeatStale, interval.toSeconds(), details);
    }

    public Optional<SourceHealthDetail> findById(Long id) {
        return sources.findById(id).map(this::healthFor);
    }
}
