package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

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
 * <p>When an entry has no guid and no link, a synthetic GUID is assigned that is
 * DETERMINISTIC - derived from stable content (the link if present, else title + published date).
 * This ensures the same entry on a later poll dedupes instead of re-inserting.
 */
@Component
public class FeedNormalizer {

    private static final Logger log = LoggerFactory.getLogger(FeedNormalizer.class);

    /** Used only if a feed genuinely has no guid, no link, and no title. */
    private static final String DEFAULT_LINK = "https://example.com/";

    /** Default published date for synthetic GUID calculation. */
    private static final String DEFAULT_PUBLISHED = "1970-01-01T00:00:00Z";

    public Item normalize(SyndEntry entry, FeedSource source, Instant fetchedAt) {
        String guid = guidOf(entry);
        String link = linkOf(entry);
        String title = titleOf(entry);
        String summary = summaryOf(entry);
        Instant publishedAt = publishedAtOf(entry);

        // If guid is still null/blank after guidOf, we need to generate a synthetic one
        if (guid == null || guid.isBlank()) {
            guid = syntheticGuid(link, title, publishedAt);
            log.debug("Entry '{}' has no guid; assigned synthetic guid: {}", title, guid);
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

    /**
     * Generate a deterministic synthetic GUID from stable content.
     *
     * <p>The derivation follows the requirement: "derived from stable content
     * (the link if present, else title + published date)".
     *
     * <p>Algorithm:
     * <ul>
     *   <li>If link is present and non-blank, use SHA-256 hash of the link</li>
     *   <li>Else, use SHA-256 hash of (title + "|" + published date)</li>
     *   <li>If title is also missing, use SHA-256 hash of default string</li>
     * </ul>
     *
     * @param link the entry link (may be null)
     * @param title the entry title (may be null or empty)
     * @param publishedAt the entry published date (may be null)
     * @return a deterministic synthetic GUID
     */
    private String syntheticGuid(String link, String title, Instant publishedAt) {
        String input;
        
        if (link != null && !link.isBlank()) {
            // Use link if present
            input = "link:" + link;
        } else {
            // Use title + published date
            String publishedStr = publishedAt != null 
                ? publishedAt.toString() 
                : DEFAULT_PUBLISHED;
            input = "title:" + (title != null ? title : "") + "|published:" + publishedStr;
        }
        
        return sha256(input);
    }

    /**
     * Compute SHA-256 hash of a string and return as hex.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            HexFormat hex = HexFormat.of();
            return hex.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
