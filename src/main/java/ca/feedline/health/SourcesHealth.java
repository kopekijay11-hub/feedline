package ca.feedline.health;

import java.time.Instant;
import java.util.List;

public record SourcesHealth(
        String status,
        Instant lastCompletedPollCycle,
        boolean heartbeatStale,
        long pollIntervalSeconds,
        List<SourceHealthDetail> sources
) {
}
