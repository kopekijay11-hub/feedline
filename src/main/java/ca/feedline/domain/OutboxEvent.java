package ca.feedline.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(nullable = false, length = 255)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 1024)
    private String eventKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "poisoned_at")
    private Instant poisonedAt;

    @Column(name = "replayed_count", nullable = false)
    private int replayedCount;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    public OutboxEvent() {
    }

    public OutboxEvent(Long aggregateId, String topic, String eventKey, String payload) {
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.eventKey = eventKey;
        this.payload = payload;
        this.attempts = 0;
        this.nextAttemptAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(String eventKey) {
        this.eventKey = eventKey;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getPoisonedAt() {
        return poisonedAt;
    }

    public int getReplayedCount() {
        return replayedCount;
    }

    public void setReplayedCount(int replayedCount) {
        this.replayedCount = replayedCount;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isPoisoned() {
        return poisonedAt != null;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    public void recordFailure(String error) {
        recordFailure(error, Instant.now());
    }

    public void recordFailure(String error, Instant attemptedAt) {
        this.attempts++;
        this.lastAttemptAt = attemptedAt;
        this.lastError = error == null ? "unknown publish failure"
                : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    public void scheduleRetry(Instant at) {
        this.nextAttemptAt = at;
    }

    public void markPoisoned(Instant at) {
        this.poisonedAt = at;
    }

    public void requeueForRetry(Instant at) {
        this.poisonedAt = null;
        this.publishedAt = null;
        this.attempts = 0;
        this.nextAttemptAt = at;
        this.lastError = null;
        this.replayedCount++;
    }
}
