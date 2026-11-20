package ca.feedline.service;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "feed.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long sendTimeoutMs;
    private final Clock clock;

    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              @Value("${feed.outbox.batch-size:50}") int batchSize,
                              @Value("${feed.outbox.max-attempts:5}") int maxAttempts,
                              @Value("${feed.outbox.initial-backoff-ms:1000}") long initialBackoffMs,
                              @Value("${feed.outbox.max-backoff-ms:60000}") long maxBackoffMs,
                              @Value("${feed.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                              Clock clock) {
        this.outboxEvents = outboxEvents;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(1, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
        this.sendTimeoutMs = Math.max(1, sendTimeoutMs);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${feed.outbox.poll-delay:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEvents.findPublishable(batchSize);
        for (OutboxEvent event : pending) {
            try {
                kafka.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted before publishing event {}; batch stopped", event.getId());
                return;
            } catch (Exception e) {
                recordFailure(event, e);
                if (event.isPoisoned()) {
                    log.warn("Outbox event {} quarantined after permanent publish failure: {}",
                            event.getId(), event.getLastError());
                    continue;
                }
                log.warn("Outbox event {} publish failed; retry delayed: {}", event.getId(), event.getLastError());
                return;
            }
        }
    }

    private void recordFailure(OutboxEvent event, Exception failure) {
        event.recordFailure(rootMessage(failure));
        if (isPermanent(failure) || event.getAttempts() >= maxAttempts) {
            event.markPoisoned(clock.instant());
            return;
        }
        event.scheduleRetry(clock.instant().plusMillis(backoffFor(event.getAttempts())));
    }

    private long backoffFor(int attempts) {
        long delay = initialBackoffMs;
        for (int attempt = 1; attempt < attempts && delay < maxBackoffMs; attempt++) {
            if (delay > maxBackoffMs / 2) {
                return maxBackoffMs;
            }
            delay *= 2;
        }
        return Math.min(delay, maxBackoffMs);
    }

    private static boolean isPermanent(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof SerializationException
                    || cursor instanceof RecordTooLargeException
                    || cursor instanceof InvalidTopicException
                    || cursor instanceof AuthenticationException
                    || cursor instanceof AuthorizationException
                    || cursor instanceof UnsupportedVersionException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getName() : message;
    }
}
