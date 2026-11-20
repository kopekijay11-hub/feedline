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
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(repository.findPublishable(10)).thenReturn(List.of(first, second));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("broker down"));
        when(kafka.send("topic-a", "key-1", "payload")).thenReturn(failed);

        relay.publishPending();

        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("broker down");
        assertThat(first.getNextAttemptAt()).isEqualTo(NOW.plusMillis(1000));
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getPoisonedAt()).isNull();
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getPublishedAt()).isNull();
        verify(kafka, never()).send("topic-a", "key-2", "payload");
    }

    @Test
    void permanentFailure_poisonsEventAndLetsLaterEventsPublishToStoredTopic() {
        OutboxEvent poison = event(1L, "topic-a", "bad-key");
        OutboxEvent good = event(2L, "topic-b", "good-key");
        when(repository.findPublishable(10)).thenReturn(List.of(poison, good));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new SerializationException("bad payload"));
        when(kafka.send("topic-a", "bad-key", "payload")).thenReturn(failed);
        CompletableFuture<SendResult<String, String>> sent = new CompletableFuture<>();
        sent.complete(null);
        when(kafka.send("topic-b", "good-key", "payload")).thenReturn(sent);

        relay.publishPending();

        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).contains("bad payload");
        assertThat(poison.getPoisonedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(good.getPublishedAt()).isEqualTo(NOW);
        assertThat(good.getAttempts()).isZero();
        verify(kafka).send("topic-b", "good-key", "payload");
    }

    @Test
    void retryExhaustion_poisonsEvent() {
        OutboxEvent event = event(1L, "topic-a", "key-1");
        event.recordFailure("previous-1");
        event.recordFailure("previous-2");
        when(repository.findPublishable(10)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("still down"));
        when(kafka.send(eq("topic-a"), eq("key-1"), anyString())).thenReturn(failed);

        relay.publishPending();

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getPoisonedAt()).isEqualTo(NOW);
        assertThat(event.getPublishedAt()).isNull();
    }

    private OutboxEvent event(long id, String topic, String key) {
        OutboxEvent event = new OutboxEvent(id, topic, key, "payload");
        event.setId(id);
        return event;
    }
}
