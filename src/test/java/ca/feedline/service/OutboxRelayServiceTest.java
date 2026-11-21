package ca.feedline.service;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.mockito.ArgumentMatchers;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class OutboxRelayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaTemplate<String, String> kafka;

    private OutboxRelayService relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelayService(repository, kafka, 10, 3, 1000, 10_000, 1000,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void transientFailure_recordsAttemptAndExponentialBackoffWithoutStuckInFlightState() {
        OutboxEvent first = event(1L, "topic-a", "key-1");
        OutboxEvent second = event(2L, "topic-a", "key-2");
        when(repository.findPublishable(10, NOW)).thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker down"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

        relay.publishPending();

        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("broker down");
        assertThat(first.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusMillis(1000));
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getPoisonedAt()).isNull();
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getPublishedAt()).isNull();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void batchSizeIsClampedToBoundedRange() {
        OutboxRelayService lower = new OutboxRelayService(repository, kafka, 0, 5, 1000, 10_000, 1000,
                Clock.fixed(NOW, ZoneOffset.UTC));
        OutboxRelayService upper = new OutboxRelayService(repository, kafka, 10_000, 5, 1000, 10_000, 1000,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findPublishable(1, NOW)).thenReturn(List.of());
        when(repository.findPublishable(1000, NOW)).thenReturn(List.of());

        lower.publishPending();
        upper.publishPending();

        verify(repository).findPublishable(1, NOW);
        verify(repository).findPublishable(1000, NOW);
    }

    @Test
    void transientFailuresBackoffExponentiallyAndStopAtMaxBackoff() {
        OutboxRelayService capped = new OutboxRelayService(repository, kafka, 10, 10, 1000, 5000, 1000,
                Clock.fixed(NOW, ZoneOffset.UTC));
        OutboxEvent event = event(1L, "topic-a", "key-1");
        when(repository.findPublishable(10, NOW)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker down"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

        capped.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusMillis(1000));
        capped.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusMillis(2000));
        capped.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusMillis(4000));
        capped.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusMillis(5000));
        capped.publishPending();
        assertThat(event.getNextAttemptAt()).isEqualTo(NOW.plusMillis(5000));
        assertThat(event.getAttempts()).isEqualTo(5);
        assertThat(event.getLastAttemptAt()).isEqualTo(NOW);
        assertThat(event.getPoisonedAt()).isNull();
    }

    @Test
    void retryAfterTransientFailurePreservesKeyAndHeaders() {
        OutboxEvent event = event(1L, "topic-a", "42:retry-1");
        event.setPayload("{\"eventKey\":\"42:retry-1\",\"schemaVersion\":1}");
        when(repository.findPublishable(10, NOW)).thenReturn(List.of(event));
        List<ProducerRecord<String, String>> captured = new java.util.ArrayList<>();
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker down"));
        CompletableFuture<SendResult<String, String>> sent = new CompletableFuture<>();
        sent.complete(null);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            captured.add(record);
            return captured.size() == 1 ? failed : sent;
        });

        relay.publishPending();
        relay.publishPending();

        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).key()).isEqualTo("42:retry-1");
        assertThat(captured.get(1).key()).isEqualTo(captured.get(0).key());
        assertThat(headerMap(captured.get(1))).isEqualTo(headerMap(captured.get(0)));
        assertThat(event.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void permanentFailure_poisonsEventAndLetsLaterEventsPublishToStoredTopic() {
        OutboxEvent poison = event(1L, "topic-a", "bad-key");
        OutboxEvent good = event(2L, "topic-b", "good-key");
        when(repository.findPublishable(10, NOW)).thenReturn(List.of(poison, good));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SerializationException("bad payload"));
        CompletableFuture<SendResult<String, String>> sent = new CompletableFuture<>();
        sent.complete(null);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed, sent);

        relay.publishPending();

        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).contains("bad payload");
        assertThat(poison.getPoisonedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(good.getPublishedAt()).isEqualTo(NOW);
        assertThat(good.getAttempts()).isZero();
    }

    @Test
    void retryExhaustion_poisonsEvent() {
        OutboxEvent event = event(1L, "topic-a", "key-1");
        event.recordFailure("previous-1");
        event.recordFailure("previous-2");
        when(repository.findPublishable(10, NOW)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("still down"));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

        relay.publishPending();

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getPoisonedAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    void requeuePoisoned_givesQuarantinedEventAFreshRetryBudget() {
        OutboxEvent poisoned = event(1L, "topic-a", "key-1");
        poisoned.recordFailure("bad payload");
        poisoned.markPoisoned(NOW.minusSeconds(30));
        when(repository.findPoisoned(10)).thenReturn(List.of(poisoned));

        int requeued = relay.requeuePoisoned();

        assertThat(requeued).isEqualTo(1);
        assertThat(poisoned.getPoisonedAt()).isNull();
        assertThat(poisoned.getAttempts()).isZero();
        assertThat(poisoned.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(poisoned.getLastError()).isNull();
        assertThat(relay.requeuedCount()).isEqualTo(1);
    }

    @Test
    void observe_reportsDuePendingPoisonedAndOldestPendingLag() {
        when(repository.countDue(NOW)).thenReturn(2L);
        when(repository.countPublishable()).thenReturn(3L);
        when(repository.countPoisoned()).thenReturn(1L);
        when(repository.oldestPendingCreatedAt()).thenReturn(NOW.minusMillis(1500));

        OutboxRelayService.RelayStats stats = relay.observe();

        assertThat(stats.due()).isEqualTo(2L);
        assertThat(stats.pending()).isEqualTo(3L);
        assertThat(stats.poisoned()).isEqualTo(1L);
        assertThat(stats.oldestPendingLag()).isEqualTo(java.time.Duration.ofMillis(1500));
    }

    @Test
    void observe_reportsZeroLagWhenNothingIsPending() {
        when(repository.oldestPendingCreatedAt()).thenReturn(null);

        assertThat(relay.observe().oldestPendingLag()).isEqualTo(java.time.Duration.ZERO);
    }

    @Test
    void publishesDeterministicHeadersAndEventKey() throws Exception {
        OutboxEvent event = event(1L, "topic-a", "42:seam-1");
        event.setSchemaVersion(1);
        event.setPayload("{\"schemaVersion\":1,\"eventKey\":\"42:seam-1\",\"eventType\":\"ItemIngested\",\"itemId\":7,\"sourceId\":42,\"guid\":\"seam-1\",\"title\":\"T\",\"link\":\"http://example.test/1\",\"publishedAt\":\"2026-11-20T00:00:00Z\",\"occurredAt\":\"2026-11-20T00:00:00Z\",\"ingestedAt\":\"2026-11-20T00:00:00Z\"}");
        AtomicReference<ProducerRecord<String, String>> captured = new AtomicReference<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            CompletableFuture<SendResult<String, String>> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        });
        when(repository.findPublishable(10, NOW)).thenReturn(List.of(event));

        relay.publishPending();

        ProducerRecord<String, String> record = captured.get();
        assertThat(record.key()).isEqualTo("42:seam-1");
        assertThat(record.value()).isEqualTo(event.getPayload());
        Map<String, String> headers = headerMap(record);
        assertThat(headers.get("schemaVersion")).isEqualTo("1");
        assertThat(headers.get("eventKey")).isEqualTo("42:seam-1");
        assertThat(headers.get("eventType")).isEqualTo("ItemIngested");
        assertThat(headers.get("occurredAt")).isEqualTo("2026-11-20T00:00:00Z");
        assertThat(headers.get("sourceId")).isEqualTo("42");
    }

    private static Map<String, String> headerMap(ProducerRecord<String, String> record) {
        Map<String, String> map = new HashMap<>();
        record.headers().forEach(header -> map.put(header.key(), new String(header.value(), StandardCharsets.UTF_8)));
        return map;
    }

    private OutboxEvent event(long id, String topic, String key) {
        OutboxEvent event = new OutboxEvent(id, topic, key, "payload");
        event.setId(id);
        return event;
    }
}
