package ca.feedline.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Documents the downstream consumer idempotency contract: deliveries are at-least-once,
 * so consumers must dedupe by the Kafka message key / {@code eventKey}. A replayed poison
 * event carries the same {@code eventKey} as the original and must not be processed twice.
 */
class ConsumerIdempotencyTest {

    @Test
    void duplicateDeliveriesWithSameEventKeyAreProcessedOnce() {
        String eventKey = "42:seam-idempotent";
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        AtomicInteger processed = new AtomicInteger();

        ConsumerRecord<String, String> initial = new ConsumerRecord<>("feedline.items.ingested", 0, 0L, eventKey,
                "{\"eventKey\":\"" + eventKey + "\",\"schemaVersion\":1}");
        ConsumerRecord<String, String> redelivery = new ConsumerRecord<>("feedline.items.ingested", 0, 1L, eventKey,
                "{\"eventKey\":\"" + eventKey + "\",\"schemaVersion\":1}");
        ConsumerRecord<String, String> poisonReplay = new ConsumerRecord<>("feedline.items.ingested", 0, 2L, eventKey,
                "{\"eventKey\":\"" + eventKey + "\",\"schemaVersion\":1}");

        for (ConsumerRecord<String, String> record : List.of(initial, redelivery, poisonReplay)) {
            if (seenKeys.add(record.key())) {
                processed.incrementAndGet();
            }
        }

        assertThat(processed.get()).isEqualTo(1);
        assertThat(seenKeys).containsExactly(eventKey);
    }

    @Test
    void distinctEventKeysAreProcessedIndependently() {
        Set<String> seenKeys = ConcurrentHashMap.newKeySet();
        AtomicInteger processed = new AtomicInteger();

        ConsumerRecord<String, String> a = new ConsumerRecord<>("feedline.items.ingested", 0, 0L, "42:seam-a", "{}");
        ConsumerRecord<String, String> b = new ConsumerRecord<>("feedline.items.ingested", 0, 1L, "42:seam-b", "{}");

        List.of(a, b).forEach(record -> {
            if (seenKeys.add(record.key())) {
                processed.incrementAndGet();
            }
        });

        assertThat(processed.get()).isEqualTo(2);
        assertThat(seenKeys).containsExactlyInAnyOrder("42:seam-a", "42:seam-b");
    }
}
