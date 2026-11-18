package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the persistence layer against a real PostgreSQL
 * container (Testcontainers). Both tests run inside
 * {@code mvn verify} (failsafe plugin) and need Docker to be installed.
 *
 * <p>M2a: Updated to test the new FeedSource fields (etag, lastModified).
 */
@Testcontainers
@SpringBootTest
class FeedPollerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.16-alpine")
            .withDatabaseName("feedline")
            .withUsername("feedline")
            .withPassword("feedline");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ItemRepository items;

    @Autowired
    private FeedSourceRepository sources;

    @BeforeEach
    void clean() {
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void savesAndReadsBackItems() {
        FeedSource source = feedSource("https://example.test/feed.xml");
        sources.save(source);

        Item item = item(source, "guid-1", "https://example.test/1", "Title One",
                "Summary one", Instant.now(), Instant.now());
        items.save(item);

        List<Item> loaded = items.findBySourceIdOrderByFetchedAtDesc(source.getId());
        assertEquals(1, loaded.size(), "exactly one item must be found for the source");
        assertEquals("Title One", loaded.get(0).getTitle());
        assertNotNull(loaded.get(0).getFetchedAt());
    }

    @Test
    void duplicateSourceGuid_isInsertIfAbsent() {
        FeedSource source = feedSource("https://example.test/feed.xml");
        sources.save(source);

        items.save(item(source, "guid-unique", "https://example.test/1", "Title",
                "Summary", Instant.now(), Instant.now()));

        Throwable thrown = null;
        try {
            items.save(item(source, "guid-unique", "https://example.test/1", "Title dup",
                    "Summary dup", Instant.now(), Instant.now()));
        } catch (Throwable t) {
            thrown = t;
        }

        // The unique (source_id, guid) constraint rejects the duplicate save and
        // the original row is left untouched - one row, not two.
        assertTrue(thrown instanceof DataIntegrityViolationException,
                "duplicate save was expected to be rejected by the unique constraint");
        assertEquals(1L, items.countBySourceIdAndGuid(source.getId(), "guid-unique"),
                "only one row may exist for a given (source, guid)");
    }

    @Test
    void feedSourceHasEtagAndLastModified() {
        FeedSource source = new FeedSource("Test Feed", "https://example.test/feed.xml");
        source.setEtag("\"abc123\"");
        source.setLastModified("Wed, 01 Jan 2026 12:00:00 GMT");
        
        sources.save(source);
        
        FeedSource loaded = sources.findByUrl("https://example.test/feed.xml").orElse(null);
        assertNotNull(loaded);
        assertEquals("\"abc123\"", loaded.getEtag());
        assertEquals("Wed, 01 Jan 2026 12:00:00 GMT", loaded.getLastModified());
    }

    @Test
    void feedSourceUrlIsUnique() {
        FeedSource source1 = feedSource("https://example.test/unique.xml");
        sources.save(source1);

        FeedSource source2 = feedSource("https://example.test/unique.xml");
        source2.setName("Different Name");

        Throwable thrown = null;
        try {
            sources.save(source2);
        } catch (Throwable t) {
            thrown = t;
        }

        assertTrue(thrown instanceof DataIntegrityViolationException,
                "duplicate URL should be rejected by the unique constraint");
        assertEquals(1L, sources.count());
    }

    private FeedSource feedSource(String url) {
        FeedSource source = new FeedSource();
        source.setName("Test feed");
        source.setUrl(url);
        source.setEnabled(true);
        return source;
    }

    private Item item(FeedSource source, String guid, String link, String title,
                      String summary, Instant publishedAt, Instant fetchedAt) {
        Item item = new Item();
        item.setSource(source);
        item.setGuid(guid);
        item.setLink(link);
        item.setTitle(title);
        item.setSummary(summary);
        item.setPublishedAt(publishedAt);
        item.setFetchedAt(fetchedAt);
        return item;
    }
}
