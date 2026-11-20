package ca.feedline.health;

import java.time.Instant;

public record SourceHealthDetail(
        Long id,
        String name,
        String state,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        String lastErrorClass,
        int consecutiveFailures,
        boolean validatorsPresent,
        Long secondsSinceLastSuccess,
        Instant lastNewItemAt,
        String contentFreshness,
        long pollIntervalSeconds,
        long skippedItemCount,
        long skippedOccurrences
) {
}
