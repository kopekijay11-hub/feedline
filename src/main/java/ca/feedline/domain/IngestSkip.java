package ca.feedline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "ingest_skips")
public class IngestSkip {

    @EmbeddedId
    private IngestSkipId id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "failure_class", nullable = false, length = 64)
    private String failureClass;

    @Column(name = "first_skipped_at", nullable = false)
    private Instant firstSkippedAt;

    @Column(name = "last_skipped_at", nullable = false)
    private Instant lastSkippedAt;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    public IngestSkip() {
    }

    public IngestSkip(IngestSkipId id, String failureClass, Instant skippedAt) {
        this.id = id;
        this.failureClass = failureClass;
        this.firstSkippedAt = skippedAt;
        this.lastSkippedAt = skippedAt;
        this.occurrences = 1;
    }

    public IngestSkipId getId() {
        return id;
    }

    public String getFailureClass() {
        return failureClass;
    }

    public void setFailureClass(String failureClass) {
        this.failureClass = failureClass;
    }

    public Instant getFirstSkippedAt() {
        return firstSkippedAt;
    }

    public Instant getLastSkippedAt() {
        return lastSkippedAt;
    }

    public void setLastSkippedAt(Instant lastSkippedAt) {
        this.lastSkippedAt = lastSkippedAt;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public void setOccurrences(int occurrences) {
        this.occurrences = occurrences;
    }
}
