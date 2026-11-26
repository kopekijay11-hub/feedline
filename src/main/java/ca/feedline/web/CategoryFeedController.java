package ca.feedline.web;

import ca.feedline.service.CategoryFeedService;
import ca.feedline.service.CategoryFeedService.FeedItem;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.SyndFeedOutput;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@RestController
public class CategoryFeedController {

    private final CategoryFeedService categoryFeedService;

    public CategoryFeedController(CategoryFeedService categoryFeedService) {
        this.categoryFeedService = categoryFeedService;
    }

    @GetMapping(value = "/feed/all.xml", produces = "application/rss+xml")
    public ResponseEntity<String> all(HttpServletRequest request) {
        return feed(request, "All", categoryFeedService.allSourceIds());
    }

    @GetMapping(value = "/feed/{category}.xml", produces = "application/rss+xml")
    public ResponseEntity<String> category(HttpServletRequest request, @PathVariable String category) {
        return categoryFeedService.bySlug(category)
                .map(cf -> feed(request, cf.name(), cf.sourceIds()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/feed/index.opml", produces = "application/xml")
    public ResponseEntity<String> opml(HttpServletRequest request) {
        List<CategoryFeedService.CategoryFeed> categories = categoryFeedService.categories();
        List<Long> allIds = categoryFeedService.allSourceIds();
        List<FeedItem> allItems = categoryFeedService.newestItems(allIds);
        String body = renderOpml(request, categories);
        return conditional(request, body, allItems, "application/xml");
    }

    private ResponseEntity<String> feed(HttpServletRequest request, String categoryName, List<Long> sourceIds) {
        List<FeedItem> items = categoryFeedService.newestItems(sourceIds);
        SyndFeed feed = new SyndFeedImpl();
        feed.setFeedType("rss_2.0");
        feed.setTitle("Feedline \u2014 " + categoryName);
        feed.setLink(dashboardRoot(request));
        feed.setDescription("Feedline aggregated feed for " + categoryName);
        feed.setLanguage("en");

        List<SyndEntry> entries = new ArrayList<>();
        for (FeedItem item : items) {
            SyndEntry entry = new SyndEntryImpl();
            entry.setTitle(item.title());
            entry.setLink(item.link());
            entry.setUri("urn:feedline:item:" + item.id());

            SyndContent description = new SyndContentImpl();
            description.setType("text/plain");
            description.setValue(item.summary() == null ? "" : item.summary());
            entry.setDescription(description);

            Instant coalesced = item.publishedAt() != null ? item.publishedAt() : item.fetchedAt();
            entry.setPublishedDate(Date.from(coalesced));

            SyndFeed source = new SyndFeedImpl();
            source.setTitle(item.sourceName());
            source.setLink(item.sourceUrl());
            entry.setSource(source);

            entries.add(entry);
        }
        feed.setEntries(entries);

        String xml;
        try {
            xml = new SyndFeedOutput().outputString(feed, true);
        } catch (Exception e) {
            throw new IllegalStateException("unable to render feed", e);
        }
        return conditional(request, xml, items, "application/rss+xml");
    }

    private ResponseEntity<String> conditional(HttpServletRequest request, String body, List<FeedItem> items,
                                             String contentType) {
        Instant newest = items.isEmpty() ? null : coalesced(items.get(0));
        String etag = newest == null
                ? "\"empty-" + body.hashCode() + "\""
                : "\"" + newest + "-" + items.size() + "\"";

        if (isNotModified(request, etag, newest)) {
            HttpHeaders headers = cacheHeaders(etag, newest);
            headers.setContentType(MediaType.parseMediaType(contentType + ";charset=UTF-8"));
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
        }

        HttpHeaders headers = cacheHeaders(etag, newest);
        headers.setContentType(MediaType.parseMediaType(contentType + ";charset=UTF-8"));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private boolean isNotModified(HttpServletRequest request, String etag, Instant newest) {
        String ifNoneMatch = request.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null) {
            for (String part : ifNoneMatch.split(",")) {
                String value = part.trim();
                if (value.equals(etag) || value.equals("W/" + etag)) {
                    return true;
                }
            }
        }
        if (newest != null) {
            String ifModifiedSince = request.getHeader(HttpHeaders.IF_MODIFIED_SINCE);
            if (ifModifiedSince != null) {
                try {
                    ZonedDateTime parsed = ZonedDateTime.parse(ifModifiedSince, DateTimeFormatter.RFC_1123_DATE_TIME);
                    if (!parsed.toInstant().isBefore(newest)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Malformed date: ignore and treat as modified.
                }
            }
        }
        return false;
    }

    private HttpHeaders cacheHeaders(String etag, Instant newest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        if (newest != null) {
            headers.setLastModified(newest);
        }
        headers.setCacheControl(CacheControl.maxAge(60, java.util.concurrent.TimeUnit.SECONDS));
        return headers;
    }

    private String dashboardRoot(HttpServletRequest request) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath("/")
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private String renderOpml(HttpServletRequest request, List<CategoryFeedService.CategoryFeed> categories) {
        String root = dashboardRoot(request);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<opml version=\"2.0\">\n");
        sb.append("  <head><title>Feedline feeds</title></head>\n");
        sb.append("  <body>\n");
        for (CategoryFeedService.CategoryFeed category : categories) {
            String url = feedUrl(request, category.slug() + ".xml");
            sb.append("    <outline type=\"rss\" text=\"")
                    .append(escapeXml("Feedline \u2014 " + category.name()))
                    .append("\" title=\"")
                    .append(escapeXml("Feedline \u2014 " + category.name()))
                    .append("\" xmlUrl=\"")
                    .append(escapeXml(url))
                    .append("\" htmlUrl=\"")
                    .append(escapeXml(root))
                    .append("\"/>\n");
        }
        String allUrl = feedUrl(request, "all.xml");
        sb.append("    <outline type=\"rss\" text=\"Feedline \u2014 All\" title=\"Feedline \u2014 All\" xmlUrl=\"")
                .append(escapeXml(allUrl))
                .append("\" htmlUrl=\"")
                .append(escapeXml(root))
                .append("\"/>\n");
        sb.append("  </body>\n");
        sb.append("</opml>\n");
        return sb.toString();
    }

    private String feedUrl(HttpServletRequest request, String file) {
        return ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath("/feed/" + file)
                .replaceQuery(null)
                .build()
                .toUriString();
    }

    private Instant coalesced(FeedItem item) {
        return item.publishedAt() != null ? item.publishedAt() : item.fetchedAt();
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
