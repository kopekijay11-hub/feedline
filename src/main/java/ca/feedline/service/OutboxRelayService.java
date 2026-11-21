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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong requeuedCount = new AtomicLong();
    private FeedlineMetrics metrics;

    @Autowired
    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              @Value("${feed.outbox.batch-size:50}") int batchSize,
                              @Value("${feed.outbox.max-attempts:5}") int maxAttempts,
                              @Value("${feed.outbox.initial-backoff-ms:1000}") long initialBackoffMs,
                              @Value("${feed.outbox.max-backoff-ms:60000}") long maxBackoffMs,
                              @Value("${feed.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                              Clock clock) {
        this(outboxEvents, kafka, batchSize, maxAttempts, initialBackoffMs, maxBackoffMs, sendTimeoutMs, clock, null);
    }

    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              @Value("${feed.outbox.batch-size:50}") int batchSize,
                              @Value("${feed.outbox.max-attempts:5}") int maxAttempts,
                              @Value("${feed.outbox.initial-backoff-ms:1000}") long initialBackoffMs,
                              @Value("${feed.outbox.max-backoff-ms:60000}") long maxBackoffMs,
                              @Value("${feed.outbox.send-timeout-ms:5000}") long sendTimeoutMs,
                              Clock clock, FeedlineMetrics metrics) {
        this.outboxEvents = outboxEvents;
        this.kafka = kafka;
        this.batchSize = batchSize;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(1, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
        this.sendTimeoutMs = Math.max(1, sendTimeoutMs);
        this.clock = clock;
        this.metrics = metrics;
    }

    @Autowired(required = false)
    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injection")
    public void setMetrics(FeedlineMetrics metrics) {
        if (metrics != null) {
            this.metrics = metrics;
        }
    }

    @Scheduled(fixedDelayString = "${feed.outbox.poll-delay:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEvents.findPublishable(batchSize, clock.instant());
        for (OutboxEvent event : pending) {
            try {
                kafka.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
                event.markPublished(clock.instant());
                publishedCount.incrementAndGet();
                if (metrics != null) {
                    metrics.recordOutboxPublished();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted before publishing event {}; batch stopped", event.getId());
                return;
            } catch (Exception e) {
                failureCount.incrementAndGet();
                recordFailure(event, e);
                if (event.isPoisoned()) {
                    if (metrics != null) {
                        metrics.recordOutboxPoisoned();
                    }
                    log.warn("Outbox event {} quarantined after permanent publish failure: {}",
                            event.getId(), event.getLastError());
                    continue;
                }
                if (metrics != null) {
                    metrics.recordOutboxRetryScheduled();
                }
                log.warn("Outbox event {} publish failed; retry delayed: {}", event.getId(), event.getLastError());
                return;
            }
        }
    }

    @Transactional
    public int requeuePoisoned() {
        List<OutboxEvent> poisoned = outboxEvents.findPoisoned(batchSize);
        for (OutboxEvent event : poisoned) {
            event.requeueForRetry(clock.instant());
            requeuedCount.incrementAndGet();
            if (metrics != null) {
                metrics.recordOutboxReplayed();
            }
            log.info("Outbox event {} requeued from poison quarantine", event.getId());
        }
        return poisoned.size();
    }

    public RelayStats observe() {
        Instant now = clock.instant();
        Instant oldest = outboxEvents.oldestPendingCreatedAt();
        return new RelayStats(
                outboxEvents.countDue(now),
                outboxEvents.countPublishable(),
                outboxEvents.countPoisoned(),
                oldest == null ? Duration.ZERO : Duration.between(oldest, now));
    }

    public long publishedCount() {
        return publishedCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    public long requeuedCount() {
        return requeuedCount.get();
    }

    private void recordFailure(OutboxEvent event, Exception failure) {
        Instant failedAt = clock.instant();
        event.recordFailure(rootMessage(failure));
        if (isPermanent(failure) || event.getAttempts() >= maxAttempts) {
            event.markPoisoned(failedAt);
            return;
        }
        event.scheduleRetry(failedAt.plusMillis(backoffFor(event.getAttempts())));
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

    public record RelayStats(long due, long pending, long poisoned, Duration oldestPendingLag) {
    }
}
