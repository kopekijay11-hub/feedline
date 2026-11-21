package ca.feedline.service;

import ca.feedline.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

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
}
