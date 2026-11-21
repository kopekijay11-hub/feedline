package ca.feedline.dto;

import java.time.Instant;

public record PoisonedOutboxEventDto(
        Long id,
        String topic,
        String eventKey,
        int attempts,
        int replayedCount,
        String lastError,
        Instant createdAt,
        Instant poisonedAt) {
}
