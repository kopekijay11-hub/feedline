package ca.feedline.dto;

import java.time.Instant;

/**
 * Operator-facing diagnostics for one poisoned outbox row.
 *
 * <p>{@code lastError} is sanitized and length-bounded before it leaves the
 * service: secrets are redacted, control characters are collapsed, and the
 * summary never carries a raw stack trace.
 */
public record PoisonedOutboxEventDto(
        Long id,
        String topic,
        String eventKey,
        int attempts,
        int replayedCount,
        String lastError,
        Instant lastAttemptAt,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant poisonedAt) {
}