package ca.feedline.service;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name = "feed.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventRepository outboxEvents;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxRelayService(OutboxEventRepository outboxEvents, KafkaTemplate<String, String> kafka,
                              @Value("${feed.outbox.topic:feedline.items.ingested}") String topic,
                              @Value("${feed.outbox.batch-size:50}") int batchSize) {
        this.outboxEvents = outboxEvents;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${feed.outbox.poll-delay:2000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> pending = outboxEvents.findByPublishedAtIsNullOrderByIdAsc(PageRequest.of(0, batchSize));
        for (OutboxEvent event : pending) {
            try {
                kafka.send(topic, event.getEventKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                outboxEvents.markPublished(event.getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Outbox relay interrupted before publishing event {}; batch stopped", event.getId());
                return;
            } catch (Exception e) {
                log.warn("Outbox relay could not publish event {}; batch stopped: {}", event.getId(), e.toString());
                return;
            }
        }
    }
}
