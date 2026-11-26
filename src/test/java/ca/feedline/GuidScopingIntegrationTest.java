package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.ItemIngestResult;
import ca.feedline.service.ItemIngestService;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
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

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests proving the GUID dedup contract: dedup is scoped per
 * {@code (source_id, guid)}, so the same GUID from two different sources is
 * stored twice, while the same source re-ingesting the same GUID is not.
 * A changed GUID between polls is stored as a new item (current behaviour,
 * documented contract, not a defect fixed here).
 */
@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "feed.scheduling-enabled=false")
class GuidScopingIntegrationTest {

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
    void sameGuidAcrossTwoSources_bothStored() {
        SyndEntry shared = entry("shared-guid", "Shared Guid Item", "https://example.test/shared");
        FeedSource sourceA = source("Source A", "https://example.test/a.xml");
        FeedSource sourceB = source("Source B", "https://example.test/b.xml");
        sources.save(sourceA);
        sources.save(sourceB);

        ItemIngestResult resultA = itemIngest.ingest(sourceA, shared, Instant.now());
        ItemIngestResult resultB = itemIngest.ingest(sourceB, shared, Instant.now());

        assertTrue(resultA.stored(), "first source must store the shared GUID");
        assertTrue(resultB.stored(), "second source must store the same GUID");
        assertEquals(2, countItems(), "same GUID from two sources yields two rows");
        assertNotNull(items.findBySourceIdAndGuid(sourceA.getId(), "shared-guid").orElse(null));
        assertNotNull(items.findBySourceIdAndGuid(sourceB.getId(), "shared-guid").orElse(null));
    }

    @Test
    void sameSourceSameGuidReingest_noDuplicate() {
        SyndEntry entry = entry("repeat-guid", "Repeat Guid Item", "https://example.test/repeat");
        FeedSource source = source("Repeat Source", "https://example.test/repeat.xml");
        sources.save(source);

        ItemIngestResult first = itemIngest.ingest(source, entry, Instant.now());
        ItemIngestResult second = itemIngest.ingest(source, entry, Instant.now());

        assertTrue(first.stored(), "first ingest must store the entry");
        assertFalse(second.stored(), "re-ingesting the same (source, guid) must not store");
        assertEquals(1, countItems(), "same source re-ingesting the same GUID yields one row");
    }

    @Test
    void changedGuidBetweenPolls_storedAsNewItem() {
        SyndEntry first = entry("guid-one", "Changed Guid Item", "https://example.test/changed");
        SyndEntry second = entry("guid-two", "Changed Guid Item", "https://example.test/changed");
        FeedSource source = source("Changed Guid Source", "https://example.test/changed.xml");
        sources.save(source);

        ItemIngestResult resultFirst = itemIngest.ingest(source, first, Instant.now());
        ItemIngestResult resultSecond = itemIngest.ingest(source, second, Instant.now());

        assertTrue(resultFirst.stored(), "first poll must store the item");
        assertTrue(resultSecond.stored(), "changed GUID must be stored as a new item");
        assertEquals(2, countItems(), "changed GUID between polls yields two rows");
    }

    private long countItems() {
        return items.count();
    }

    private FeedSource source(String name, String url) {
        FeedSource source = new FeedSource(name, url);
        source.setEnabled(true);
        return source;
    }

    private SyndEntry entry(String guid, String title, String link) {
        SyndEntryImpl entry = new SyndEntryImpl();
        entry.setUri(guid);
        entry.setTitle(title);
        entry.setLink(link);
        SyndContent description = new SyndContentImpl();
        description.setValue("summary");
        entry.setDescription(description);
        entry.setPublishedDate(java.util.Date.from(Instant.parse("2026-01-01T12:00:00Z")));
        return entry;
    }
}
