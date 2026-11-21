package ca.feedline;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.FeedlineMetrics;
import ca.feedline.service.OutboxCleanupService;
import ca.feedline.service.OutboxRelayService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxCleanupIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.16-alpine")
            .withDatabaseName("feedline")
            .withUsername("feedline")
            .withPassword("feedline");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("feed.poll-delay", () -> "86400000");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.outbox.enabled", () -> "true");
        registry.add("feed.outbox.cleanup.enabled", () -> "true");
        registry.add("feed.outbox.cleanup.batch-size", () -> "2");
        registry.add("feed.outbox.cleanup.published-retention", () -> "7d");
    }

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private OutboxCleanupService cleanupService;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private MeterRegistry meterRegistry;

    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");

    @BeforeEach
    void setUp() {
        outboxEvents.deleteAll();
    }

    @AfterEach
    void tearDown() {
        outboxEvents.deleteAll();
    }

    @Test
    void cleanupDeletesOnlyExpiredPublishedRowsInBatches() {
        Instant old = NOW.minusSeconds(30L * 24 * 3600);
        for (int i = 0; i < 5; i++) {
            OutboxEvent e = new OutboxEvent((long) i, "topic", "key-" + i, "payload");
            e.markPublished(old);
            outboxEvents.save(e);
        }
        // recent published
        OutboxEvent recent = new OutboxEvent(100L, "topic", "recent", "payload");
        recent.markPublished(NOW.minusSeconds(3600));
        outboxEvents.save(recent);
        // pending unpublished (old created_at not relevant)
        OutboxEvent pending = new OutboxEvent(101L, "topic", "pending", "payload");
        outboxEvents.save(pending);
        // poisoned
        OutboxEvent poisoned = new OutboxEvent(102L, "topic", "poisoned", "payload");
        poisoned.markPoisoned(NOW.minusSeconds(1000));
        outboxEvents.save(poisoned);

        // Use fixed clock? cleanupService uses system clock; retention cutoff based on now. Old enough.
        int deleted = cleanupService.cleanup();

        assertThat(deleted).isEqualTo(5);
        List<OutboxEvent> remaining = outboxEvents.findAll();
        assertThat(remaining).hasSize(3);
        assertThat(remaining).extracting(OutboxEvent::getEventKey)
                .containsExactlyInAnyOrder("recent", "pending", "poisoned");
    }

    @Test
    void poisonReplayIncrementsReplayedCountAndMetric() {
        OutboxEvent poisoned = new OutboxEvent(200L, "topic", "poison", "payload");
        poisoned.recordFailure("bad");
        poisoned.markPoisoned(NOW.minusSeconds(10));
        outboxEvents.save(poisoned);

        int requeued = relayService.requeuePoisoned();

        assertThat(requeued).isEqualTo(1);
        OutboxEvent reloaded = outboxEvents.findById(poisoned.getId()).orElseThrow();
        assertThat(reloaded.getReplayedCount()).isEqualTo(1);
        assertThat(reloaded.getPoisonedAt()).isNull();

        double replayed = meterRegistry.get("feedline.outbox.events.total").tag("outcome", "replayed").counter().count();
        assertThat(replayed).isEqualTo(1.0);
    }
}
