package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.ItemIngestResult;
import ca.feedline.service.ItemIngestService;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.io.SyndFeedInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for dedupe functionality through the real ingest seam.
 *
 * <p>Tests that ingesting the same fixture twice does not create duplicate rows.
 * Includes a no-GUID entry to prove the deterministic synthetic GUID works.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "feed.scheduling-enabled=false")
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
        registry.add("feed.poll-delay", () -> "86400000");
        registry.add("feed.ssrf-allow-loopback", () -> "true");
    }

    @Autowired
    private ItemRepository items;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemIngestService itemIngest;

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
    void ingestSameFixtureTwice_noDuplicates() throws Exception {
        List<SyndEntry> entries = loadEntries("/fixtures/dedupe-test.xml");
        FeedSource source = source("Dedupe Test", "https://example.test/dedupe.xml");
        sources.save(source);

        for (SyndEntry entry : entries) {
            ItemIngestResult result = itemIngest.ingest(source, entry, Instant.now());
            assertTrue(result.stored(), "first ingest must store entry " + result.guid());
        }
        assertEquals(3, countItems(), "first ingest must store three items");

        for (SyndEntry entry : entries) {
            ItemIngestResult result = itemIngest.ingest(source, entry, Instant.now());
            assertFalse(result.stored(), "second ingest must not restore entry " + result.guid());
        }
        assertEquals(3, countItems(), "second ingest must not create duplicate rows");

        Optional<Item> noGuidItem = items.findByGuid("https://example.com/no-guid-but-has-link");
        assertTrue(noGuidItem.isPresent(), "No-GUID item should exist");
    }

    @Test
    void ingestEquivalentNoGuidEntries_dedupesViaSyntheticGuid() {
        SyndEntryImpl first = entry();
        SyndEntryImpl second = entry();
        FeedSource source = source("Synthetic GUID Test", "https://example.test/synthetic.xml");
        sources.save(source);

        ItemIngestResult firstResult = itemIngest.ingest(source, first, Instant.now());
        ItemIngestResult secondResult = itemIngest.ingest(source, second, Instant.now());

        assertTrue(firstResult.stored());
        assertFalse(secondResult.stored());
        assertEquals(firstResult.guid(), secondResult.guid(),
                "equivalent entries must derive the same synthetic GUID");
        assertNotNull(firstResult.guid());
        assertFalse(firstResult.guid().isBlank());
        assertEquals(1, countItems());
    }

    private long countItems() {
        return items.count();
    }

    private FeedSource source(String name, String url) {
        FeedSource source = new FeedSource(name, url);
        source.setEnabled(true);
        return source;
    }

    private SyndEntryImpl entry() {
        SyndEntryImpl entry = new SyndEntryImpl();
        entry.setTitle("Synthetic GUID Test");
        entry.setPublishedDate(java.util.Date.from(Instant.parse("2026-01-01T12:00:00Z")));
        return entry;
    }

    private List<SyndEntry> loadEntries(String path) throws Exception {
        URL resource = getClass().getResource(path);
        assertNotNull(resource, "fixture not found: " + path);
        try (Reader reader = new InputStreamReader(resource.openStream(), "UTF-8")) {
            SyndFeedInput input = new SyndFeedInput();
            List<SyndEntry> entries = input.build(reader).getEntries();
            assertNotNull(entries);
            return entries;
        }
    }
}
