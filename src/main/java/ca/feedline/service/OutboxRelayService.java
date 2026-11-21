package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressFBWarnings({"REC_CATCH_EXCEPTION"})
@Service
@ConditionalOnProperty(name = "feed.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);

    static final int MIN_BATCH_SIZE = 1;
    static final int MAX_BATCH_SIZE = 1000;
    static final long MIN_BACKOFF_MS = 1L;
    static final long MAX_BACKOFF_MS = 3_600_000L;
    static final long MAX_SEND_TIMEOUT_MS = 60_000L;

    private static final String HEADER_SCHEMA_VERSION = "schemaVersion";
    private static final String HEADER_EVENT_KEY = "eventKey";
    private static final String HEADER_EVENT_TYPE = "eventType";
    private static final String HEADER_OCCURRED_AT = "occurredAt";
    private static final String HEADER_SOURCE_ID = "sourceId";

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long sendTimeoutMs;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong publishedCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong requeuedCount = new AtomicLong();
    private FeedlineMetrics metrics;

    @Autowired
    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              FeedProperties feedProperties, Clock clock) {
        this(outboxEvents, kafka, feedProperties.getOutbox(), clock, null);
    }

    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              FeedProperties.Outbox settings, Clock clock, FeedlineMetrics metrics) {
        this(outboxEvents, kafka, settings.getBatchSize(), settings.getMaxAttempts(),
                settings.getInitialBackoffMs(), settings.getMaxBackoffMs(), settings.getSendTimeoutMs(),
                clock, metrics);
    }

    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              int batchSize,
                              int maxAttempts,
                              long initialBackoffMs,
                              long maxBackoffMs,
                              long sendTimeoutMs,
                              Clock clock) {
        this(outboxEvents, kafka, batchSize, maxAttempts, initialBackoffMs, maxBackoffMs, sendTimeoutMs, clock, null);
    }

    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              int batchSize,
                              int maxAttempts,
                              long initialBackoffMs,
                              long maxBackoffMs,
                              long sendTimeoutMs,
                              Clock clock, FeedlineMetrics metrics) {
        this.outboxEvents = outboxEvents;
        this.kafka = kafka;
        this.batchSize = Math.max(MIN_BATCH_SIZE, Math.min(batchSize, MAX_BATCH_SIZE));
        this.maxAttempts = Math.max(1, Math.min(maxAttempts, 100));
        this.initialBackoffMs = Math.max(MIN_BACKOFF_MS, Math.min(initialBackoffMs, MAX_BACKOFF_MS));
        this.maxBackoffMs = Math.max(this.initialBackoffMs, Math.min(maxBackoffMs, MAX_BACKOFF_MS));
        this.sendTimeoutMs = Math.max(MIN_BACKOFF_MS, Math.min(sendTimeoutMs, MAX_SEND_TIMEOUT_MS));
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
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        event.getTopic(), partitionKey(event), event.getPayload());
                record.headers().add(new RecordHeader(HEADER_SCHEMA_VERSION,
                        String.valueOf(event.getSchemaVersion()).getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader(HEADER_EVENT_KEY,
                        event.getEventKey().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader(HEADER_EVENT_TYPE,
                        eventType(event).getBytes(StandardCharsets.UTF_8)));
                Instant occurredAt = occurredAt(event);
                if (occurredAt != null) {
                    record.headers().add(new RecordHeader(HEADER_OCCURRED_AT,
                            occurredAt.toString().getBytes(StandardCharsets.UTF_8)));
                }
                Long sourceId = sourceId(event);
                if (sourceId != null) {
                    record.headers().add(new RecordHeader(HEADER_SOURCE_ID,
                            sourceId.toString().getBytes(StandardCharsets.UTF_8)));
                }
                kafka.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
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


    private String eventType(OutboxEvent event) {
        return "ItemIngested";
    }

    /**
     * Kafka partition key. The deterministic {@code sourceId:guid} value is used
     * for both the record key and the {@code eventKey} header, so a retry or an
     * admin replay of the same row lands on the same partition in the same order.
     */
    private static String partitionKey(OutboxEvent event) {
        return event.getEventKey();
    }

    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "defensive JSON parsing")
    private Instant occurredAt(OutboxEvent event) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(event.getPayload());
            for (String field : new String[] {"occurredAt", "ingestedAt", "publishedAt"}) {
                if (node.hasNonNull(field)) {
                    return Instant.parse(node.get(field).asText());
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
        } catch (RuntimeException ignored) {
        }
        return event.getCreatedAt();
    }

    private Long sourceId(OutboxEvent event) {
        String key = event.getEventKey();
        if (key == null) {
            return null;
        }
        int idx = key.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        try {
            return Long.parseLong(key.substring(0, idx));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "defensive")
    private void recordFailure(OutboxEvent event, Exception failure) {
        Instant failedAt = clock.instant();
        event.recordFailure(rootMessage(failure), failedAt);
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
