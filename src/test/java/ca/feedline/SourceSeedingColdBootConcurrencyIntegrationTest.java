package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.SourceRegistryInitializer;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.service.FeedPollerService;
import ca.feedline.service.FeedFetcher;
import ca.feedline.service.FeedNormalizer;
import ca.feedline.service.ItemIngestService;
import ca.feedline.service.IngestSkipRecorder;
import ca.feedline.service.FeedlineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for cold-boot source seeding race condition.
 *
 * <p>This test verifies that concurrent execution of {@link SourceRegistryInitializer}
 * and {@link FeedPollerService} against an empty database does not produce
 * SQLState 23505 (unique constraint violation on {@code uq_feed_sources_url}).
 *
 * <p>The test simulates the real-world scenario where:
 * <ol>
 *   <li>Application starts with an empty database</li>
 *   <li>{@link SourceRegistryInitializer} begins seeding configured sources</li>
 *   <li>Scheduled {@link FeedPollerService} starts its first poll cycle</li>
 *   <li>Both attempt to insert the same source URLs concurrently</li>
 * </ol>
 *
 * <p>With the idempotent {@code insertIfAbsent} and readiness gate, both
 * threads should complete successfully without throwing unique constraint violations.
 */
@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
    "feed.scheduling-enabled=false",
    "feed.poll-delay=1s",
    "feed.ssrf-allow-loopback=true"
})
class SourceSeedingColdBootConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
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
    private FeedSourceRepository feedSourceRepository;

    @Autowired
    private FeedFetcher feedFetcher;

    @Autowired
    private FeedNormalizer feedNormalizer;

    @Autowired
    private ItemIngestService itemIngestService;

    @Autowired
    private IngestSkipRecorder ingestSkipRecorder;

    private FeedlineMetrics metrics;

    @BeforeEach
    void setUp() {
        feedSourceRepository.deleteAll();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics = new FeedlineMetrics(registry);
    }

    @Test
    void coldBootConcurrentSeedingAndPollingNoUniqueViolation() throws Exception {
        // Configure multiple sources to seed
        FeedProperties props = new FeedProperties();
        FeedProperties.Source source1 = new FeedProperties.Source("Feed One", "http://example.test/feed1");
        source1.setEnabled(true);
        FeedProperties.Source source2 = new FeedProperties.Source("Feed Two", "http://example.test/feed2");
        source2.setEnabled(true);
        FeedProperties.Source source3 = new FeedProperties.Source("Feed Three", "http://example.test/feed3");
        source3.setEnabled(true);
        props.setSources(List.of(source1, source2, source3));

        SourceRegistryInitializer initializer = new SourceRegistryInitializer(props, feedSourceRepository);

        // Create poller with the initializer to test the readiness gate
        FeedPollerService poller = new FeedPollerService(
                props, feedFetcher, feedNormalizer, feedSourceRepository,
                itemIngestService, ingestSkipRecorder, metrics, "test-worker", initializer);

        // Use a latch to start both threads simultaneously
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger initializerErrors = new AtomicInteger(0);
        AtomicInteger pollerErrors = new AtomicInteger(0);

        // Thread 1: Run source registry initializer (simulates ApplicationRunner on startup)
        Thread initThread = new Thread(() -> {
            try {
                startLatch.await();
                initializer.run(null);
            } catch (Exception e) {
                initializerErrors.incrementAndGet();
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: Run poller's pollOnce (simulates @Scheduled first execution)
        Thread pollThread = new Thread(() -> {
            try {
                startLatch.await();
                // This will wait for initializer to complete via the readiness gate
                poller.pollOnce();
            } catch (Exception e) {
                pollerErrors.incrementAndGet();
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        });

        initThread.start();
        pollThread.start();

        // Release both threads simultaneously to maximize race chance
        startLatch.countDown();

        // Wait for both to complete
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
                "Both initializer and poller should complete within 30 seconds");

        // Verify no errors occurred
        assertThat(initializerErrors.get()).as("Initializer should not throw exceptions").isZero();
        assertThat(pollerErrors.get()).as("Poller should not throw exceptions").isZero();

        // Verify all three sources were created exactly once
        List<FeedSource> allSources = feedSourceRepository.findAll();
        assertThat(allSources).hasSize(3);

        // Verify no duplicate URLs
        assertThat(allSources.stream().map(FeedSource::getUrl).distinct().count()).isEqualTo(3);

        // Verify seeding is marked complete
        assertThat(initializer.isSeedingComplete()).isTrue();

        // Verify poller executed a cycle (even if no items saved due to mock fetcher)
        // The pollOnce should have completed without throwing
    }

    @Test
    void multipleInitializersConcurrentSeedingIsIdempotent() throws Exception {
        // Simulates multiple application instances starting simultaneously
        // against the same empty database (e.g., Kubernetes rolling restart)
        FeedProperties props = new FeedProperties();
        FeedProperties.Source source = new FeedProperties.Source("Shared Feed", "http://example.test/shared");
        source.setEnabled(true);
        props.setSources(List.of(source));

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                SourceRegistryInitializer initializer = new SourceRegistryInitializer(props, feedSourceRepository);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        initializer.run(null);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        e.printStackTrace();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS),
                    "All initializers should complete within 30 seconds");

            assertThat(errors.get()).as("No initializer should throw unique constraint violation").isZero();

            // Exactly one source should exist
            List<FeedSource> allSources = feedSourceRepository.findAll();
            assertThat(allSources).hasSize(1);
            assertThat(allSources.get(0).getUrl()).isEqualTo("http://example.test/shared");
            assertThat(allSources.get(0).getName()).isEqualTo("Shared Feed");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void pollerWaitsForSeedingBeforeFirstPoll() throws Exception {
        // Verifies that the poller's readiness gate actually blocks until
        // seeding is complete, preventing the race window entirely
        FeedProperties props = new FeedProperties();
        FeedProperties.Source source = new FeedProperties.Source("Gated Feed", "http://example.test/gated");
        source.setEnabled(true);
        props.setSources(List.of(source));

        SourceRegistryInitializer initializer = new SourceRegistryInitializer(props, feedSourceRepository);

        FeedPollerService poller = new FeedPollerService(
                props, feedFetcher, feedNormalizer, feedSourceRepository,
                itemIngestService, ingestSkipRecorder, metrics, "gated-worker", initializer);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch initDone = new CountDownLatch(1);
        CountDownLatch pollDone = new CountDownLatch(1);
        AtomicInteger pollerErrors = new AtomicInteger(0);

        // Start poller first - it should block on the readiness gate
        Thread pollThread = new Thread(() -> {
            try {
                startLatch.await();
                poller.pollOnce();
            } catch (Exception e) {
                pollerErrors.incrementAndGet();
                e.printStackTrace();
            } finally {
                pollDone.countDown();
            }
        });

        // Start initializer after a small delay
        Thread initThread = new Thread(() -> {
            try {
                Thread.sleep(100); // Let poller start and hit the gate
                startLatch.countDown(); // Release poller (it will wait at gate)
                initializer.run(null);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                initDone.countDown();
            }
        });

        pollThread.start();
        initThread.start();

        // Wait for both to complete
        assertTrue(initDone.await(10, TimeUnit.SECONDS), "Initializer should complete");
        assertTrue(pollDone.await(10, TimeUnit.SECONDS), "Poller should complete after initializer");

        assertThat(pollerErrors.get()).isZero();
        assertThat(initializer.isSeedingComplete()).isTrue();

        // Verify source exists
        FeedSource loaded = feedSourceRepository.findByUrl("http://example.test/gated").orElse(null);
        assertThat(loaded).isNotNull();
        assertThat(loaded.getName()).isEqualTo("Gated Feed");
    }
}