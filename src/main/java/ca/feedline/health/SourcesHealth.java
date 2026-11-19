package ca.feedline.health;

import java.time.Instant;
import java.util.List;

public record SourcesHealth(
        String status,
        String sourcesStatus,
        Instant lastCompletedPollCycle,
        boolean heartbeatStale,
        long pollIntervalSeconds,
        List<SourceHealthDetail> sources
) {

    public SourcesHealth withStatus(String newStatus) {
        return new SourcesHealth(newStatus, sourcesStatus, lastCompletedPollCycle,
                heartbeatStale, pollIntervalSeconds, sources);
    }
}
