package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "feed.outbox.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanupService {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupService.class);
    private static final int MAX_BATCHES = 1000;

    private final OutboxEventRepository outboxEvents;
    private final FeedProperties feedProperties;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring injection")
    public OutboxCleanupService(OutboxEventRepository outboxEvents, FeedProperties feedProperties, Clock clock) {
        this.outboxEvents = outboxEvents;
        this.feedProperties = feedProperties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Scheduled(cron = "${feed.outbox.cleanup.cron:0 0 * * * *}")
    public void scheduledCleanup() {
        cleanup();
    }

    @Transactional
    public int cleanup() {
        return doCleanup();
    }

    @Transactional
    public int runCleanup() {
        return doCleanup();
    }

    @Transactional
    public int purge() {
        return doCleanup();
    }

    @Transactional
    public int cleanUp() {
        return doCleanup();
    }

    private int doCleanup() {
        if (!feedProperties.getOutbox().getCleanup().isEnabled()) {
            return 0;
        }
        int batchSize = Math.max(1, feedProperties.getOutbox().getCleanup().getBatchSize());
        Instant cutoff = clock.instant().minus(feedProperties.getOutbox().getCleanup().getPublishedRetention());
        int totalDeleted = 0;
        int batches = 0;
        while (batches < MAX_BATCHES) {
            int deleted = outboxEvents.deletePublishedBefore(cutoff, batchSize);
            totalDeleted += deleted;
            batches++;
            if (deleted < batchSize) {
                break;
            }
        }
        if (totalDeleted > 0) {
            log.info("Outbox cleanup deleted {} published rows older than {}", totalDeleted, cutoff);
        } else {
            log.debug("Outbox cleanup deleted 0 published rows older than {}", cutoff);
        }
        return totalDeleted;
    }
}
