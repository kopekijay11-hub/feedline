package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;

public class FeedNormalizer {

    private static final Logger log = LoggerFactory.getLogger(FeedNormalizer.class);

    private static final String DEFAULT_LINK = "https://example.com/";
    private static final String DEFAULT_PUBLISHED = "1970-01-01T00:00:00Z";

    private static final int DEFAULT_TITLE_MAX = 1000;
    private static final int DEFAULT_SUMMARY_MAX = 4000;
    private static final int DEFAULT_LINK_MAX = 1024;
    private static final int DEFAULT_GUID_MAX = 512;

    private final int titleMax;
    private final int summaryMax;
    private final int linkMax;
    private final int guidMax;

    public FeedNormalizer() {
        this(DEFAULT_TITLE_MAX, DEFAULT_SUMMARY_MAX, DEFAULT_LINK_MAX, DEFAULT_GUID_MAX);
    }

    public FeedNormalizer(int titleMax, int summaryMax, int linkMax) {
        this(titleMax, summaryMax, linkMax, DEFAULT_GUID_MAX);
    }

    public FeedNormalizer(int titleMax, int summaryMax, int linkMax, int guidMax) {
        this.titleMax = titleMax > 0 ? titleMax : DEFAULT_TITLE_MAX;
        this.summaryMax = summaryMax > 0 ? summaryMax : DEFAULT_SUMMARY_MAX;
        this.linkMax = linkMax > 0 ? linkMax : DEFAULT_LINK_MAX;
        this.guidMax = guidMax > 0 ? guidMax : DEFAULT_GUID_MAX;
    }

    public FeedNormalizer(FeedProperties.Normalizer normalizer) {
        this(normalizer == null ? DEFAULT_TITLE_MAX : normalizer.getTitleMaxLength(),
                normalizer == null ? DEFAULT_SUMMARY_MAX : normalizer.getSummaryMaxLength(),
                normalizer == null ? DEFAULT_LINK_MAX : normalizer.getLinkMaxLength(),
                normalizer == null ? DEFAULT_GUID_MAX : normalizer.getGuidMaxLength());
    }

    public int getTitleMax() {
        return titleMax;
    }

    public int getTitleMaxLength() {
        return titleMax;
    }

    public int getSummaryMax() {
        return summaryMax;
    }

    public int getSummaryMaxLength() {
        return summaryMax;
    }

    public int getLinkMax() {
        return linkMax;
    }

    public int getLinkMaxLength() {
        return linkMax;
    }

    public int getGuidMax() {
        return guidMax;
    }

    public int getGuidMaxLength() {
        return guidMax;
    }

    public Item normalize(SyndEntry entry, FeedSource source, Instant fetchedAt) {
        return normalize(entry, source, fetchedAt, -1);
    }

    public Item normalize(SyndEntry entry, FeedSource source, Instant fetchedAt, int index) {
        String guid = guidFor(entry, index);
        String link = linkOf(entry);
        String title = titleOf(entry);
        String summary = summaryOf(entry);
        Instant publishedAt = publishedAtOf(entry);

        if (link == null || link.isBlank()) {
            link = DEFAULT_LINK;
            log.debug("Entry '{}' has no link; assigned default link", title);
        }

        return new Item(source, guid, link, title, summary, publishedAt, fetchedAt);
    }

    public String guidFor(SyndEntry entry) {
        return guidFor(entry, -1);
    }

    public String guidFor(SyndEntry entry, int index) {
        String guid = sanitize(guidOf(entry));
        if (guid != null && !guid.isBlank()) {
            return cap(guid, guidMax);
        }
        String synthetic = syntheticGuid(linkOf(entry), titleOf(entry), publishedAtOf(entry), index);
        log.debug("Entry '{}' has no guid; assigned synthetic guid: {}", titleOf(entry), synthetic);
        return synthetic;
    }

    private String guidOf(SyndEntry entry) {
        String uri = entry.getUri();
        if (uri != null && !uri.isBlank()) {
            return uri;
        }
        return linkOf(entry);
    }

    private String linkOf(SyndEntry entry) {
        String link = entry.getLink();
        String sanitized = sanitize(link);
        return sanitized == null || sanitized.isBlank() ? null : sanitized;
    }

    private String titleOf(SyndEntry entry) {
        String title = entry.getTitle();
        String sanitized = sanitize(title);
        if (sanitized == null || sanitized.isBlank()) {
            sanitized = "";
        }
        return cap(sanitized, titleMax);
    }

    private String summaryOf(SyndEntry entry) {
        String value = null;
        SyndContent description = entry.getDescription();
        if (description != null && description.getValue() != null && !description.getValue().isBlank()) {
            value = description.getValue();
        }
        if (value == null || value.isBlank()) {
            List<SyndContent> contents = entry.getContents();
            if (contents != null) {
                for (SyndContent content : contents) {
                    if (content != null && content.getValue() != null && !content.getValue().isBlank()) {
                        value = content.getValue();
                        break;
                    }
                }
            }
        }
        if (value == null || value.isBlank()) {
            return null;
        }
        return cap(sanitize(value), summaryMax);
    }

    private Instant publishedAtOf(SyndEntry entry) {
        java.util.Date published = entry.getPublishedDate();
        if (published != null) {
            return published.toInstant();
        }
        java.util.Date updated = entry.getUpdatedDate();
        return updated == null ? null : updated.toInstant();
    }

    private String syntheticGuid(String link, String title, Instant publishedAt, int index) {
        String sanitizedLink = sanitize(link);
        String sanitizedTitle = sanitize(title);
        String input;
        if (sanitizedLink != null && !sanitizedLink.isBlank()) {
            input = "link:" + sanitizedLink;
        } else {
            String publishedStr = publishedAt != null ? publishedAt.toString() : DEFAULT_PUBLISHED;
            input = "title:" + (sanitizedTitle == null ? "" : sanitizedTitle) + "|published:" + publishedStr;
        }
        if (index >= 0) {
            input = input + "|index:" + index;
        }
        return sha256(input);
    }

    private String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String s = value.replaceAll("<[^>]*>", "");
        s = HtmlUtils.htmlUnescape(s);
        s = s.replace("\u0000", "");
        s = s.trim();
        if (s.isEmpty()) {
            return null;
        }
        return s;
    }

    private String cap(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            HexFormat hex = HexFormat.of();
            return hex.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
