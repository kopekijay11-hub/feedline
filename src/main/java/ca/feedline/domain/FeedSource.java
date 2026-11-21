package ca.feedline.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One feed registered in the {@code feed_sources} table.
 *
 * <p>The {@code url} column is declared unique so that a feed configuration is
 * only ever inserted once (insert-if-absent on the registry).
 */
@Entity
@Table(name = "feed_sources")
public class FeedSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String url;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "etag", columnDefinition = "TEXT")
    private String etag;

    @Column(name = "last_modified", columnDefinition = "TEXT")
    private String lastModified;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error_class", length = 64)
    private String lastErrorClass;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "last_new_item_at")
    private Instant lastNewItemAt;

    @Column(name = "next_poll_at")
    private Instant nextPollAt;

    @Column(name = "lease_owner", length = 128)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    public FeedSource() {
    }

    public FeedSource(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public String getLastErrorClass() {
        return lastErrorClass;
    }

    public void setLastErrorClass(String lastErrorClass) {
        this.lastErrorClass = lastErrorClass;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public void setLastErrorAt(Instant lastErrorAt) {
        this.lastErrorAt = lastErrorAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Instant getLastNewItemAt() {
        return lastNewItemAt;
    }

    public void setLastNewItemAt(Instant lastNewItemAt) {
        this.lastNewItemAt = lastNewItemAt;
    }

    public Instant getNextPollAt() {
        return nextPollAt;
    }

    public void setNextPollAt(Instant nextPollAt) {
        this.nextPollAt = nextPollAt;
    }

    public boolean isBackoffActive(Instant now) {
        return enabled && nextPollAt != null && nextPollAt.isAfter(now);
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    // Alias for poll-attempt timestamp used by due/lease queries.
    public Instant getLastPolledAt() {
        return lastAttemptAt;
    }

    public void setLastPolledAt(Instant lastPolledAt) {
        this.lastAttemptAt = lastPolledAt;
    }

    public boolean isLeaseExpired(Instant now) {
        return leaseUntil == null || !leaseUntil.isAfter(now);
    }
}
