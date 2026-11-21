package ca.feedline.service;

import ca.feedline.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class FeedlineMetrics {

    private final MeterRegistry registry;
    private final OutboxEventRepository repository;
    private final Clock clock;

    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong poisonedCount = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
    private final AtomicLong activeSourceCount = new AtomicLong();
    private final Map<String, AtomicLong> sourceHealthCounts = new ConcurrentHashMap<>();

    private static final List<String> SOURCE_HEALTH_STATES =
            List.of("healthy", "degraded", "backoff", "disabled", "unknown");

    public FeedlineMetrics(MeterRegistry registry) {
        this(registry, null, Clock.systemUTC());
    }

    public FeedlineMetrics(MeterRegistry registry, OutboxEventRepository repository) {
        this(registry, repository, Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    @SuppressFBWarnings(value = {"MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR", "EI_EXPOSE_REP2"}, justification = "Spring component")
    public FeedlineMetrics(MeterRegistry registry, OutboxEventRepository repository, Clock clock) {
        this(Optional.ofNullable(registry), repository, clock);
    }

    @SuppressFBWarnings(value = {"MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR", "EI_EXPOSE_REP2"}, justification = "Spring component")
    public FeedlineMetrics(Optional<MeterRegistry> registry, OutboxEventRepository repository, Clock clock) {
        this.registry = registry.orElseGet(SimpleMeterRegistry::new);
        this.repository = repository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        registerGauges();
    }

    private void registerGauges() {
        if (repository != null) {
            Gauge.builder("feedline.outbox.pending.count", repository, r -> (double) r.countPublishable())
                    .register(registry);
            Gauge.builder("feedline.outbox.poisoned.count", repository, r -> (double) r.countPoisoned())
                    .register(registry);
            Gauge.builder("feedline.outbox.oldest.pending.age.seconds", repository, r -> {
                Instant oldest = r.oldestPendingCreatedAt();
                if (oldest == null) {
                    return 0.0;
                }
                long seconds = Duration.between(oldest, clock.instant()).getSeconds();
                return seconds < 0 ? 0.0 : (double) seconds;
            }).register(registry);
        } else {
            Gauge.builder("feedline.outbox.pending.count", pendingCount, a -> (double) a.get())
                    .register(registry);
            Gauge.builder("feedline.outbox.poisoned.count", poisonedCount, a -> (double) a.get())
                    .register(registry);
            Gauge.builder("feedline.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds, a -> (double) a.get())
                    .register(registry);
        }
        Gauge.builder("feedline.sources.active.count", activeSourceCount, a -> (double) a.get())
                .register(registry);
        for (String state : SOURCE_HEALTH_STATES) {
            AtomicLong value = new AtomicLong();
            sourceHealthCounts.put(state, value);
            Gauge.builder("feedline.sources.health", value, AtomicLong::get)
                    .tag("state", state)
                    .register(registry);
        }
    }

    private void increment(String meter, String outcome) {
        registry.counter(meter, "outcome", outcome).increment();
    }

    public void recordOutboxEvent(String outcome) {
        increment("feedline.outbox.events.total", outcome);
    }

    public void incrementOutboxEvent(String outcome) {
        recordOutboxEvent(outcome);
    }

    public void outboxEvent(String outcome) {
        recordOutboxEvent(outcome);
    }

    public void recordOutboxPublished() {
        recordOutboxEvent("published");
    }

    public void recordOutboxRetryScheduled() {
        recordOutboxEvent("retry_scheduled");
    }

    public void recordOutboxPoisoned() {
        recordOutboxEvent("poisoned");
    }

    public void recordOutboxReplayed() {
        recordOutboxEvent("replayed");
    }

    public void recordIngestItem(String outcome) {
        increment("feedline.ingest.items.total", outcome);
    }

    public void incrementIngestItem(String outcome) {
        recordIngestItem(outcome);
    }

    public void ingestItem(String outcome) {
        recordIngestItem(outcome);
    }

    public void recordIngestInserted() {
        recordIngestItem("inserted");
    }

    public void recordIngestDuplicate() {
        recordIngestItem("duplicate");
    }

    public void recordIngestSkipped() {
        recordIngestItem("skipped");
    }

    public void recordFetchRequest(String outcome) {
        increment("feedline.fetch.requests.total", outcome);
    }

    public void incrementFetchRequest(String outcome) {
        recordFetchRequest(outcome);
    }

    public void fetchRequest(String outcome) {
        recordFetchRequest(outcome);
    }

    public void recordFetchOk() {
        recordFetchRequest("ok");
    }

    public void recordFetchNotModified() {
        recordFetchRequest("not_modified");
    }

    public void recordFetchError() {
        recordFetchRequest("error");
    }

    public void recordFetchDuration(long millis) {
        registry.timer("feedline.fetch.duration").record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordFetchDuration(Duration duration) {
        if (duration != null) {
            registry.timer("feedline.fetch.duration").record(duration);
        }
    }

    public Timer.Sample startFetchTimer() {
        return Timer.start(registry);
    }

    public Timer.Sample fetchTimerSample() {
        return Timer.start(registry);
    }

    public void stopFetchTimer(Timer.Sample sample) {
        if (sample != null) {
            sample.stop(registry.timer("feedline.fetch.duration"));
        }
    }

    public void setPendingCount(long value) {
        pendingCount.set(value);
    }

    public void setPoisonedCount(long value) {
        poisonedCount.set(value);
    }

    public void setOldestPendingAgeSeconds(long value) {
        oldestPendingAgeSeconds.set(value);
    }

    public void recordPollerLeaseClaimed() {
        registry.counter("feedline.poller.leases.claimed.total").increment();
    }

    public void recordPollerLeasesClaimed() {
        recordPollerLeaseClaimed();
    }

    public void recordPollerLeaseClaimed(int count) {
        for (int i = 0; i < Math.max(0, count); i++) {
            recordPollerLeaseClaimed();
        }
    }

    public void recordLeaseClaimed() {
        recordPollerLeaseClaimed();
    }

    public void recordPollerLeaseSkipped() {
        registry.counter("feedline.poller.leases.skipped.total").increment();
    }

    public void recordPollerLeasesSkipped() {
        recordPollerLeaseSkipped();
    }

    public void recordPollerLeaseSkipped(int count) {
        for (int i = 0; i < Math.max(0, count); i++) {
            recordPollerLeaseSkipped();
        }
    }

    public void recordLeaseSkipped() {
        recordPollerLeaseSkipped();
    }

    /**
     * Record one source poll outcome. Only the fixed low-cardinality set
     * {@code success}, {@code not_modified}, {@code rate_limited}, {@code error}
     * is accepted; unknown values are bucketed as {@code error}.
     */
    public void recordPollOutcome(String outcome) {
        String normalized = switch (outcome == null ? "" : outcome) {
            case "success", "not_modified", "rate_limited" -> outcome;
            default -> "error";
        };
        registry.counter("feedline.poller.poll.total", "outcome", normalized).increment();
    }

    public void recordPollSuccess() {
        recordPollOutcome("success");
    }

    public void recordPollNotModified() {
        recordPollOutcome("not_modified");
    }

    public void recordPollRateLimited() {
        recordPollOutcome("rate_limited");
    }

    public void recordPollError() {
        recordPollOutcome("error");
    }

    /**
     * Update the source-health gauges from the latest aggregate. State tags are
     * restricted to the fixed set registered in the constructor.
     */
    public void updateSourceHealth(Map<String, Long> counts, long activeSources) {
        activeSourceCount.set(Math.max(0L, activeSources));
        for (String state : SOURCE_HEALTH_STATES) {
            AtomicLong value = sourceHealthCounts.get(state);
            if (value != null) {
                long count = counts == null ? 0L : counts.getOrDefault(state, 0L);
                value.set(Math.max(0L, count));
            }
        }
    }

    public void recordShutdownLeaseRelease(int released) {
        if (released > 0) {
            registry.counter("feedline.poller.leases.released.total").increment(released);
        }
        registry.counter("feedline.shutdown.total", "outcome", "lease_released").increment();
    }

    public void recordShutdownLeaseReleaseFailed() {
        registry.counter("feedline.shutdown.total", "outcome", "lease_release_failed").increment();
    }

    public void recordShutdown(String outcome) {
        String normalized = switch (outcome == null ? "" : outcome) {
            case "relay_stopped", "cleanup_stopped", "completed" -> outcome;
            default -> "completed";
        };
        registry.counter("feedline.shutdown.total", "outcome", normalized).increment();
    }
}
