package ca.feedline.service;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.dto.OutboxStatsDto;
import ca.feedline.dto.PoisonedOutboxEventDto;
import ca.feedline.dto.PoisonedOutboxPage;
import ca.feedline.dto.PoisonReplayResponse;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.web.OutboxEventNotFoundException;
import ca.feedline.web.OutboxEventNotPoisonedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class AdminOutboxService {

    private static final Logger log = LoggerFactory.getLogger(AdminOutboxService.class);

    private final OutboxEventRepository outboxEvents;
    private final FeedlineMetrics metrics;
    private final Clock clock;
    private final OutboxCleanupService cleanupService;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injection")
    public AdminOutboxService(OutboxEventRepository outboxEvents, FeedlineMetrics metrics, Clock clock,
                              @org.springframework.beans.factory.annotation.Autowired(required = false) OutboxCleanupService cleanupService) {
        this.outboxEvents = outboxEvents;
        this.metrics = metrics;
        this.clock = clock;
        this.cleanupService = cleanupService;
    }

    @Transactional(readOnly = true)
    public PoisonedOutboxPage listPoisoned(int page, int size, String topic) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        long total = outboxEvents.countPoisonedFiltered(topic);
        int offset = safePage * safeSize;
        List<OutboxEvent> rows = outboxEvents.findPoisonedOrdered(topic, safeSize, offset);
        List<PoisonedOutboxEventDto> items = rows.stream().map(this::toDto).toList();
        int totalPages = (int) Math.ceil((double) total / (double) safeSize);
        return new PoisonedOutboxPage(items, safePage, safeSize, total, totalPages);
    }

    @Transactional
    public PoisonReplayResponse replayOne(Long id) {
        OutboxEvent event = outboxEvents.findById(id)
                .orElseThrow(() -> new OutboxEventNotFoundException(id));
        if (event.isPublished() || !event.isPoisoned()) {
            throw new OutboxEventNotPoisonedException(id);
        }
        event.requeueForRetry(clock.instant());
        outboxEvents.save(event);
        if (metrics != null) {
            metrics.recordOutboxReplayed();
        }
        log.info("Admin replayed poisoned outbox event {}", id);
        return new PoisonReplayResponse(1, List.of(id), List.of());
    }

    @Transactional
    public PoisonReplayResponse replayBatch(String topic, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<OutboxEvent> poisoned = outboxEvents.findOldestPoisoned(topic, safeLimit);
        List<Long> replayedIds = new java.util.ArrayList<>();
        List<Long> skippedIds = new java.util.ArrayList<>();
        Instant now = clock.instant();
        for (OutboxEvent event : poisoned) {
            if (event.isPublished() || !event.isPoisoned()) {
                skippedIds.add(event.getId());
                continue;
            }
            event.requeueForRetry(now);
            outboxEvents.save(event);
            replayedIds.add(event.getId());
            if (metrics != null) {
                metrics.recordOutboxReplayed();
            }
        }
        log.info("Admin replayed {} poisoned outbox events; skipped {}: {}",
                replayedIds.size(), skippedIds.size(), skippedIds);
        return new PoisonReplayResponse(replayedIds.size(), replayedIds, skippedIds);
    }



    @Transactional(readOnly = true)
    public OutboxStatsDto stats() {
        return new OutboxStatsDto(outboxEvents.countPending(), outboxEvents.countPublished(), outboxEvents.countPoisoned());
    }

    @Transactional
    public int cleanupPublished() {
        if (cleanupService == null) {
            throw new IllegalStateException("Outbox cleanup is not enabled");
        }
        return cleanupService.runCleanup();
    }
    private PoisonedOutboxEventDto toDto(OutboxEvent event) {
        return new PoisonedOutboxEventDto(
                event.getId(),
                event.getTopic(),
                event.getEventKey(),
                event.getAttempts(),
                event.getReplayedCount(),
                PoisonErrorSanitizer.sanitize(event.getLastError()),
                event.getLastAttemptAt(),
                event.getNextAttemptAt(),
                event.getCreatedAt(),
                event.getPoisonedAt());
    }
}
