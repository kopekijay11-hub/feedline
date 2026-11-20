package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "feed.scheduling-enabled=false")
class ItemIngestConcurrencyTest {

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
    private ItemIngestService ingest;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ca.feedline.repository.ItemRepository items;

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
    void concurrentIngest_duplicateGuid_insertsOnceAndLeavesCleanTransactions() throws Exception {
        FeedSource source = sources.save(new FeedSource("Concurrent", "http://example.test/concurrent"));
        CountDownLatch start = new CountDownLatch(1);
        Callable<ItemIngestResult> task = () -> {
            start.await();
            return ingest.ingest(source, entry("shared-guid"), Instant.now());
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ItemIngestResult> first = pool.submit(task);
            Future<ItemIngestResult> second = pool.submit(task);
            start.countDown();

            List<Boolean> stored = List.of(
                    first.get(30, TimeUnit.SECONDS).stored(),
                    second.get(30, TimeUnit.SECONDS).stored());

            assertThat(stored).containsExactlyInAnyOrder(true, false);
            assertThat(items.countBySourceIdAndGuid(source.getId(), "shared-guid")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(ingest.ingest(source, entry("after-guid"), Instant.now()).stored()).isTrue();
        assertThat(items.count()).isEqualTo(2);
    }

    private static SyndEntry entry(String guid) {
        SyndEntryImpl entry = new SyndEntryImpl();
        entry.setUri(guid);
        entry.setLink("http://example.test/" + guid);
        entry.setTitle("Item " + guid);
        return entry;
    }
}
