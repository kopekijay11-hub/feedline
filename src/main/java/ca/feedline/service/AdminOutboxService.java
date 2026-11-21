package ca.feedline.service;

import ca.feedline.domain.OutboxEvent;
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

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injection")
    public AdminOutboxService(OutboxEventRepository outboxEvents, FeedlineMetrics metrics, Clock clock) {
        this.outboxEvents = outboxEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PoisonedOutboxPage listPoisoned(int page, int size, String topic) {
        long total = outboxEvents.countPoisonedFiltered(topic);
        int offset = page * size;
        List<OutboxEvent> rows = outboxEvents.findPoisonedOrdered(topic, size, offset);
        List<PoisonedOutboxEventDto> items = rows.stream().map(this::toDto).toList();
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / (double) size);
        return new PoisonedOutboxPage(items, page, size, total, totalPages);
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
        List<OutboxEvent> poisoned = outboxEvents.findOldestPoisoned(topic, limit);
        List<Long> replayedIds = new java.util.ArrayList<>();
        Instant now = clock.instant();
        for (OutboxEvent event : poisoned) {
            event.requeueForRetry(now);
            outboxEvents.save(event);
            replayedIds.add(event.getId());
            if (metrics != null) {
                metrics.recordOutboxReplayed();
            }
        }
        log.info("Admin replayed {} poisoned outbox events: {}", replayedIds.size(), replayedIds);
        return new PoisonReplayResponse(replayedIds.size(), replayedIds, List.of());
    }

    private PoisonedOutboxEventDto toDto(OutboxEvent event) {
        return new PoisonedOutboxEventDto(
                event.getId(),
                event.getTopic(),
                event.getEventKey(),
                event.getAttempts(),
                event.getReplayedCount(),
                event.getLastError(),
                event.getCreatedAt(),
                event.getPoisonedAt());
    }
}
