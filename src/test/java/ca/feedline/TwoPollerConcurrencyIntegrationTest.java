package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.FeedProperties.Poller;
import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.FeedFetcher;
import ca.feedline.service.FeedNormalizer;
import ca.feedline.service.FeedPollerService;
import ca.feedline.service.IngestSkipRecorder;
import ca.feedline.service.ItemIngestService;
import ca.feedline.service.FeedlineMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "feed.scheduling-enabled=false")
class TwoPollerConcurrencyIntegrationTest {

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
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private FeedFetcher fetcher;

    @Autowired
    private FeedNormalizer normalizer;

    @Autowired
    private ItemIngestService itemIngest;

    @Autowired
    private IngestSkipRecorder skipRecorder;

    @Autowired
    private FeedlineMetrics metrics;

    private HttpServer server;
    private int port;
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        items.deleteAll();
        sources.deleteAll();
        requestCount.set(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/feed", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                String body = "<rss version=\"2.0\"><channel><title>T</title><link>http://x</link>"
                        + "<description>d</description><item><guid>g1</guid><title>One</title>"
                        + "<link>http://x/1</link></item></channel></rss>";
                byte[] bytes = body.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        items.deleteAll();
        sources.deleteAll();
    }

    private String feedUrl() {
        return "http://localhost:" + port + "/feed";
    }

    @Test
    void zeroDuplicateFetchesUnderContention() throws Exception {
        int sourceCount = 3;
        for (int i = 0; i < sourceCount; i++) {
            FeedSource s = new FeedSource("Feed " + i, feedUrl() + "?s=" + i);
            s.setEnabled(true);
            // make URLs distinct but same handler path ignored; append query still hits /feed? Actually context path /feed; query okay.
            sources.save(s);
        }
        // Fix URLs to actual endpoint (query handled by same context)
        // no-op

        FeedProperties p1 = new FeedProperties();
        Poller poller1 = new Poller();
        poller1.setWorkerId("worker-1");
        poller1.setLeaseDuration(java.time.Duration.ofMinutes(2));
        p1.setPoller(poller1);

        FeedProperties p2 = new FeedProperties();
        Poller poller2 = new Poller();
        poller2.setWorkerId("worker-2");
        poller2.setLeaseDuration(java.time.Duration.ofMinutes(2));
        p2.setPoller(poller2);

        FeedPollerService first = new FeedPollerService(p1, fetcher, normalizer, sources, itemIngest, skipRecorder, metrics, null, null);
        FeedPollerService second = new FeedPollerService(p2, fetcher, normalizer, sources, itemIngest, skipRecorder, metrics, null, null);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<?> f1 = workers.submit(() -> {
                await(start);
                first.pollOnce();
            });
            Future<?> f2 = workers.submit(() -> {
                await(start);
                second.pollOnce();
            });
            start.countDown();
            f1.get(60, TimeUnit.SECONDS);
            f2.get(60, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        assertThat(requestCount.get()).isEqualTo(sourceCount);
        List<FeedSource> all = sources.findAll();
        assertThat(all).hasSize(sourceCount);
        for (FeedSource s : all) {
            assertThat(s.getLeaseOwner()).isNull();
            assertThat(s.getLeaseUntil()).isNull();
        }
    }

    @Test
    void expiredLeaseRecovery() throws Exception {
        FeedSource source = new FeedSource("Expired", feedUrl());
        source.setEnabled(true);
        source.setLeaseOwner("crashed-node");
        source.setLeaseUntil(Instant.now().minusSeconds(10));
        sources.save(source);

        FeedSource active = new FeedSource("Active", feedUrl() + "?active");
        active.setEnabled(true);
        active.setLeaseOwner("other-node");
        active.setLeaseUntil(Instant.now().plusSeconds(600));
        sources.save(active);

        int before = requestCount.get();
        FeedProperties p = new FeedProperties();
        Poller poller = new Poller();
        poller.setWorkerId("worker-live");
        p.setPoller(poller);
        FeedPollerService live = new FeedPollerService(p, fetcher, normalizer, sources, itemIngest, skipRecorder, metrics, null, null);
        live.pollOnce();

        assertThat(requestCount.get()).isEqualTo(before + 1);

        FeedSource reloadedExpired = sources.findById(source.getId()).orElseThrow();
        assertThat(reloadedExpired.getLeaseOwner()).isNull();
        assertThat(reloadedExpired.getLeaseUntil()).isNull();

        FeedSource reloadedActive = sources.findById(active.getId()).orElseThrow();
        assertThat(reloadedActive.getLeaseOwner()).isEqualTo("other-node");
        assertThat(reloadedActive.getLeaseUntil()).isAfter(Instant.now());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
