package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FeedNormalizer} against a captured real feed.
 *
 * <p>The real feed XML is fetched once (see REPORT-M1.md) and stored under
 * {@code src/test/resources/fixtures/}. The tests run against that file - never
 * against the live network. A synthetic entry without a guid is added to exercise
 * the "missing GUID falls back to the entry link" rule.
 */
class FeedNormalizerTest {

    private FeedNormalizer normalizer;
    private FeedSource source;

    @BeforeEach
    void setUp() {
        normalizer = new FeedNormalizer();
        source = new FeedSource();
        source.setId(1L);
        source.setName("CBC Top Stories");
        source.setUrl("https://www.cbc.ca/webfeed/rss/rss-topstories");
        source.setEnabled(true);
    }

    @Test
    void normalizesCapturedFeedWithNoDataLoss() throws Exception {
        List<SyndEntry> entries = loadEntries("/fixtures/cbc-top-stories.xml");
        Instant fetchedAt = Instant.parse("2026-11-12T12:00:00Z");

        assertEquals(entries.size(), 20, "captured feed must contain 20 items");

        for (SyndEntry entry : entries) {
            Item item = normalizer.normalize(entry, source, fetchedAt);

            assertNotNull(item.getGuid(), "entry '" + entry.getTitle() + "' must have a guid");
            assertFalse(item.getGuid().isBlank(), "guid must not be blank");

            assertNotNull(item.getLink(), "entry '" + entry.getTitle() + "' must have a link");
            assertFalse(item.getLink().isBlank(), "link must not be blank");

            assertNotNull(item.getTitle(), "title must not be null");
            assertTrue(item.getTitle().length() > 0, "title must not be empty");

            assertNotNull(item.getPublishedAt(), "published_at must not be null");
            assertEquals(fetchedAt, item.getFetchedAt(), "fetched_at must equal the injected time");
            assertEquals(source, item.getSource(), "source must be the injected source");
        }
    }

    @Test
    void missingGuidFallsBackToLink() {
        SyndEntry entry = entryWithoutGuid("https://example.com/stories/2026/no-guid-story");

        Item item = normalizer.normalize(entry, source, Instant.now());

        assertEquals("https://example.com/stories/2026/no-guid-story", item.getGuid(),
                "missing guid must fall back to the entry link");
        assertEquals("https://example.com/stories/2026/no-guid-story", item.getLink());
        assertEquals("No-GUID Story", item.getTitle());
    }

    @Test
    void emptyGuidAndLinkGetDefaultValue() {
        SyndEntry entry = new SyndEntryImpl();
        entry.setTitle("Empty story");

        Item item = normalizer.normalize(entry, source, Instant.now());

        assertNotNull(item.getGuid(), "synthetic guid must be assigned");
        assertTrue(item.getGuid().length() > 0);
        assertEquals("https://example.com/", item.getLink());
        assertEquals("Empty story", item.getTitle());
    }

    private List<SyndEntry> loadEntries(String path) throws Exception {
        URL resource = getClass().getResource(path);
        assertNotNull(resource, "fixture not found: " + path);
        try (Reader reader = new InputStreamReader(resource.openStream(), "UTF-8")) {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(reader);
            List<SyndEntry> entries = feed.getEntries();
            assertNotNull(entries);
            return entries;
        }
    }

    private SyndEntry entryWithoutGuid(String link) {
        SyndEntry entry = new SyndEntryImpl();
        entry.setTitle("No-GUID Story");
        SyndContent description = new SyndContentImpl();
        description.setType("text/plain");
        description.setValue("<p>A story with a link but no guid.</p>");
        entry.setDescription(description);
        entry.setLink(link);
        return entry;
    }
}
