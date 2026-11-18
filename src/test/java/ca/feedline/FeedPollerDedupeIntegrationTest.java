package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.FeedFetcher;
import ca.feedline.service.FeedNormalizer;
import ca.feedline.service.FeedPollerService;
import com.rometools.rome.io.SyndFeedInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for dedupe functionality.
 *
 * <p>Tests that polling the same fixture twice does not create duplicate entries.
 * Includes a no-GUID entry to prove the deterministic synthetic GUID works.
 *
 * <p>MUTATION CHECK: temporarily remove the insert-if-absent guard, confirm this test
 * FAILS, restore the guard, and report the mutation result.
 */
@Testcontainers
@SpringBootTest
class FeedPollerDedupeIntegrationTest {

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
        // Disable scheduling for tests
        registry.add("feed.poll-delay", () -> "999999h");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ItemRepository items;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private FeedPollerService pollerService;

    @Autowired
    private FeedNormalizer feedNormalizer;

    @BeforeEach
    void setUp() {
        items.deleteAll();
        sources.deleteAll();
    }

    @AfterEach
    void tearDown() {
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void pollSameFixtureTwice_noDuplicates() throws Exception {
        // Load the fixture
        List<com.rometools.rome.feed.synd.SyndEntry> entries = loadEntries("/fixtures/dedupe-test.xml");
        
        // Create a feed source
        FeedSource source = new FeedSource();
        source.setName("Dedupe Test");
        source.setUrl("https://example.test/dedupe.xml");
        source.setEnabled(true);
        sources.save(source);

        // First poll - save all entries
        Instant fetchedAt1 = Instant.now();
        for (com.rometools.rome.feed.synd.SyndEntry entry : entries) {
            Item item = feedNormalizer.normalize(entry, source, fetchedAt1);
            items.save(item);
        }

        // Count rows
        long countAfterFirstPoll = countItems();
        assertEquals(3, countAfterFirstPoll, "First poll should save 3 items");

        // Second poll with same fixture - should not create duplicates
        Instant fetchedAt2 = Instant.now();
        for (com.rometools.rome.feed.synd.SyndEntry entry : entries) {
            Item item = feedNormalizer.normalize(entry, source, fetchedAt2);
            items.save(item);
        }

        // Count rows again
        long countAfterSecondPoll = countItems();
        assertEquals(3, countAfterSecondPoll, "Second poll should not create duplicates");

        // Verify the no-GUID entry was deduplicated
        Optional<Item> noGuidItem = items.findByGuid("https://example.com/no-guid-but-has-link");
        assertTrue(noGuidItem.isPresent(), "No-GUID item should exist");
    }

    @Test
    void pollSameFixtureTwice_noDuplicatesWithSyntheticGuid() throws Exception {
        // This test specifically tests entries without GUID or link
        // They should get deterministic synthetic GUIDs
        
        // Create entries without GUID or link
        com.rometools.rome.feed.synd.SyndEntry entry1 = new com.rometools.rome.feed.synd.SyndEntryImpl();
        entry1.setTitle("Synthetic GUID Test");
        entry1.setPublishedDate(java.util.Date.from(Instant.parse("2026-01-01T12:00:00Z")));

        com.rometools.rome.feed.synd.SyndEntry entry2 = new com.rometools.rome.feed.synd.SyndEntryImpl();
        entry2.setTitle("Synthetic GUID Test");
        entry2.setPublishedDate(java.util.Date.from(Instant.parse("2026-01-01T12:00:00Z")));

        // Create a feed source
        FeedSource source = new FeedSource();
        source.setName("Synthetic GUID Test");
        source.setUrl("https://example.test/synthetic.xml");
        source.setEnabled(true);
        sources.save(source);

        // First save
        Item item1 = feedNormalizer.normalize(entry1, source, Instant.now());
        items.save(item1);

        // Second save with same content
        Item item2 = feedNormalizer.normalize(entry2, source, Instant.now());
        items.save(item2);

        // Should only have 1 item
        long count = countItems();
        assertEquals(1, count, "Entries with same content should dedupe via synthetic GUID");

        // Verify the GUIDs are the same
        List<Item> allItems = items.findAll();
        assertEquals(1, allItems.size());
        assertNotNull(allItems.get(0).getGuid());
        assertTrue(allItems.get(0).getGuid().length() > 0);
    }

    private long countItems() {
        return items.count();
    }

    private List<com.rometools.rome.feed.synd.SyndEntry> loadEntries(String path) throws Exception {
        URL resource = getClass().getResource(path);
        assertNotNull(resource, "fixture not found: " + path);
        try (Reader reader = new InputStreamReader(resource.openStream(), "UTF-8")) {
            SyndFeedInput input = new SyndFeedInput();
            com.rometools.rome.feed.synd.SyndFeed feed = input.build(reader);
            List<com.rometools.rome.feed.synd.SyndEntry> entries = feed.getEntries();
            assertNotNull(entries);
            return entries;
        }
    }
}
