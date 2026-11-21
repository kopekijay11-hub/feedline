package ca.feedline.service;

import ca.feedline.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeedlineMetricsTest {

    @Test
    void countersTrackOutboxIngestAndFetchOutcomes() {
        MeterRegistry registry = new SimpleMeterRegistry();
        FeedlineMetrics metrics = new FeedlineMetrics(registry);

        metrics.recordOutboxPublished();
        metrics.recordOutboxRetryScheduled();
        metrics.recordOutboxPoisoned();
        metrics.recordOutboxReplayed();
        metrics.recordIngestInserted();
        metrics.recordIngestDuplicate();
        metrics.recordIngestSkipped();
        metrics.recordFetchOk();
        metrics.recordFetchNotModified();
        metrics.recordFetchError();

        assertThat(registry.counter("feedline.outbox.events.total", "outcome", "published").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.outbox.events.total", "outcome", "retry_scheduled").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.outbox.events.total", "outcome", "poisoned").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.outbox.events.total", "outcome", "replayed").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.ingest.items.total", "outcome", "inserted").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.ingest.items.total", "outcome", "duplicate").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.ingest.items.total", "outcome", "skipped").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.fetch.requests.total", "outcome", "ok").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.fetch.requests.total", "outcome", "not_modified").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.fetch.requests.total", "outcome", "error").count()).isEqualTo(1.0);
    }

    @Test
    void gaugesReflectRepositoryState() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventRepository repo = Mockito.mock(OutboxEventRepository.class);
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Mockito.when(repo.countPublishable()).thenReturn(3L);
        Mockito.when(repo.countPoisoned()).thenReturn(2L);
        Mockito.when(repo.oldestPendingCreatedAt()).thenReturn(now.minusSeconds(90));

        FeedlineMetrics metrics = new FeedlineMetrics(registry, repo, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(registry.get("feedline.outbox.pending.count").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("feedline.outbox.poisoned.count").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("feedline.outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(90.0);
    }

    @Test
    void oldestPendingAgeIsZeroWhenNonePending() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxEventRepository repo = Mockito.mock(OutboxEventRepository.class);
        Instant now = Instant.parse("2026-11-20T00:00:00Z");
        Mockito.when(repo.countPublishable()).thenReturn(0L);
        Mockito.when(repo.countPoisoned()).thenReturn(0L);
        Mockito.when(repo.oldestPendingCreatedAt()).thenReturn(null);

        new FeedlineMetrics(registry, repo, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(registry.get("feedline.outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void pollHealthAndShutdownMetricsAreLowCardinality() {
        MeterRegistry registry = new SimpleMeterRegistry();
        FeedlineMetrics metrics = new FeedlineMetrics(registry);

        metrics.recordPollSuccess();
        metrics.recordPollNotModified();
        metrics.recordPollRateLimited();
        metrics.recordPollError();
        metrics.recordPollOutcome("unexpected-value");
        metrics.recordShutdownLeaseRelease(3);
        metrics.recordShutdown("relay_stopped");
        metrics.recordShutdown("cleanup_stopped");

        assertThat(registry.counter("feedline.poller.poll.total", "outcome", "success").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.poller.poll.total", "outcome", "not_modified").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.poller.poll.total", "outcome", "rate_limited").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.poller.poll.total", "outcome", "error").count()).isEqualTo(2.0);
        assertThat(registry.counter("feedline.poller.leases.released.total").count()).isEqualTo(3.0);
        assertThat(registry.counter("feedline.shutdown.total", "outcome", "lease_released").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.shutdown.total", "outcome", "relay_stopped").count()).isEqualTo(1.0);
        assertThat(registry.counter("feedline.shutdown.total", "outcome", "cleanup_stopped").count()).isEqualTo(1.0);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("healthy", 2L);
        counts.put("degraded", 1L);
        counts.put("backoff", 1L);
        counts.put("disabled", 1L);
        counts.put("unknown", 1L);
        metrics.updateSourceHealth(counts, 5L);

        assertThat(registry.get("feedline.sources.active.count").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("feedline.sources.health").tag("state", "healthy").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("feedline.sources.health").tag("state", "degraded").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("feedline.sources.health").tag("state", "backoff").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("feedline.sources.health").tag("state", "disabled").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("feedline.sources.health").tag("state", "unknown").gauge().value()).isEqualTo(1.0);
    }
}
