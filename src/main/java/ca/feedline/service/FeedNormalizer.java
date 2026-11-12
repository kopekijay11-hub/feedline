package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Turns a Rome {@link SyndEntry} into an {@link Item}.
 *
 * <p>In this version of Rome (1.18) the RSS {@code <guid>} element is carried by
 * the feed processor as the entry <i>uri</i> (see
 * {@link SyndEntry#getUri()}) while the article link is carried as the entry
 * <i>link</i> (see {@link SyndEntry#getLink()}). The guid is therefore read
 * from {@code entry.getUri()} and, only when that is missing, the entry link is
 * used as the fallback for the guid - as required by the project spec.
 *
 * <p>When an entry really has neither a guid nor a link, a synthetic GUID is
 * assigned so the unique (source_id, guid) key of the items table is never
 * violated by a null.
 */
@Component
public class FeedNormalizer {

    private static final Logger log = LoggerFactory.getLogger(FeedNormalizer.class);

    /** Used only if a feed genuinely has no guid and no link. */
    private static final String DEFAULT_LINK = "https://example.com/";

    public Item normalize(SyndEntry entry, FeedSource source, Instant fetchedAt) {
        String guid = guidOf(entry);
        String link = linkOf(entry);
        String title = titleOf(entry);
        String summary = summaryOf(entry);
        Instant publishedAt = publishedAtOf(entry);

        if (guid == null || guid.isBlank()) {
            guid = java.util.UUID.randomUUID().toString();
            log.debug("Entry '{}' has no guid; assigned synthetic guid", title);
        }
        if (link == null || link.isBlank()) {
            link = DEFAULT_LINK;
            log.debug("Entry '{}' has no link; assigned default link", title);
        }

        return new Item(source, guid, link, title, summary, publishedAt, fetchedAt);
    }

    /**
     * GUID of the entry: Rome exposes the RSS {@code <guid>} value as the entry
     * uri, so the uri is preferred and the entry link is the documented fall
     * back when the guid is missing.
     */
    private String guidOf(SyndEntry entry) {
        String uri = entry.getUri();
        if (uri != null && !uri.isBlank()) {
            return uri;
        }
        return linkOf(entry);
    }

    private String linkOf(SyndEntry entry) {
        String link = entry.getLink();
        return link == null || link.isBlank() ? null : link;
    }

    private String titleOf(SyndEntry entry) {
        String title = entry.getTitle();
        return title == null ? "" : title;
    }

    private String summaryOf(SyndEntry entry) {
        SyndContent description = entry.getDescription();
        if (description == null) {
            return null;
        }
        String value = description.getValue();
        return value == null || value.isBlank() ? null : value;
    }

    private Instant publishedAtOf(SyndEntry entry) {
        java.util.Date published = entry.getPublishedDate();
        if (published != null) {
            return published.toInstant();
        }
        java.util.Date updated = entry.getUpdatedDate();
        return updated == null ? null : updated.toInstant();
    }
}
