package ca.feedline.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One parsed feed item, stored with insert-if-absent semantics.
 *
 * <p>The unique constraint on {@code (source_id, guid)} is the enforcement
 * mechanism of the feed poller: a second save of the same (source, guid) is
 * rejected and the first row keeps its identity. Do not weaken this constraint.
 */
@Entity
@Table(name = "items",
        uniqueConstraints = @UniqueConstraint(name = "uq_items_source_guid", columnNames = { "source_id", "guid" }))
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private FeedSource source;

    @Column(name = "guid", nullable = false, length = 512)
    private String guid;

    @Column(name = "link", nullable = false, length = 1024)
    private String link;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    public Item() {
    }

    public Item(FeedSource source, String guid, String link, String title,
                String summary, Instant publishedAt, Instant fetchedAt) {
        this.source = source;
        this.guid = guid;
        this.link = link;
        this.title = title;
        this.summary = summary;
        this.publishedAt = publishedAt;
        this.fetchedAt = fetchedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FeedSource getSource() {
        return source;
    }

    public void setSource(FeedSource source) {
        this.source = source;
    }

    public String getGuid() {
        return guid;
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
