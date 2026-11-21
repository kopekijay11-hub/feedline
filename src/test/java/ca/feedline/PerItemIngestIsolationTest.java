package ca.feedline;

import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.IngestSkip;
import ca.feedline.domain.IngestSkipId;
import ca.feedline.health.SourceHealthDetail;
import ca.feedline.health.SourceHealthService;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.FeedNormalizer;
import ca.feedline.service.FeedPollerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PerItemIngestIsolationTest {

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
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.ssrf-allow-loopback", () -> "true");
    }

    @Autowired
    private FeedPollerService poller;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private IngestSkipRepository skips;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private SourceHealthService health;

    @SpyBean
    private FeedNormalizer normalizer;

    @SpyBean
    private ca.feedline.service.IngestSkipRecorder skipRecorder;

    private HttpServer server;
    private int port;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>();
    private final AtomicReference<String> lastModified = new AtomicReference<>();
    private final AtomicReference<String> seenIfNoneMatch = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        skips.deleteAll();
        outboxEvents.deleteAll();
        items.deleteAll();
        sources.deleteAll();
        body.set("");
        etag.set(null);
        lastModified.set(null);
        seenIfNoneMatch.set("UNSET");
        doCallRealMethod().when(normalizer).normalize(any(), any(), any());
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
        server.createContext("/feed", this::serveFeed);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        skips.deleteAll();
        outboxEvents.deleteAll();
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void overLengthTitle_isSkippedInOwnTransaction_goodItemsCommitAndValidatorsDoNotAdvance() {
        etag.set("\"poison-etag\"");
        body.set(feed(entry("good-1", "Good one"),
                entry("poison-long", "poison-long"),
                entry("good-2", "Good two")));
        doAnswer(invocation -> {
            ca.feedline.domain.Item item = (ca.feedline.domain.Item) invocation.callRealMethod();
            if ("poison-long".equals(item.getGuid())) {
                item.setTitle("x".repeat(1001));
            }
            return item;
        }).when(normalizer).normalize(any(), any(), any());

        int saved = poller.pollSingleSource(source());

        assertThat(saved).isEqualTo(2);
        assertThat(items.findAll()).hasSize(2);
        assertThat(outboxEvents.findAll()).hasSize(2);
        assertThat(skips.findAll()).singleElement().satisfies(skip -> {
            assertThat(skip.getId().getGuid()).isEqualTo("poison-long");
            assertThat(skip.getFailureClass()).isEqualTo("TITLE_TOO_LONG");
            assertThat(skip.getOccurrences()).isEqualTo(1);
        });
        SourceHealthDetail detail = health.aggregate().sources().stream()
                .filter(item -> "Isolation Fixture".equals(item.name()))
                .findFirst()
                .orElseThrow();
        assertThat(detail.skippedItemCount()).isEqualTo(1);
        assertThat(detail.skippedOccurrences()).isEqualTo(1);
        ca.feedline.domain.FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
        assertThat(stored.getEtag()).isNull();
        assertThat(stored.getLastModified()).isNull();
        assertThat(stored.getLastSuccessAt()).isNotNull();
    }

    @Test
    void nulByteItem_isSkippedInOwnTransaction_goodItemsCommitAndValidatorsDoNotAdvance() {
        etag.set("\"nul-etag\"");
        body.set(feed(entry("good-1", "Good one"),
                entry("nul-poison", "Nul poison"),
                entry("good-2", "Good two")));
        doAnswer(invocation -> {
            ca.feedline.domain.Item item = (ca.feedline.domain.Item) invocation.callRealMethod();
            if ("nul-poison".equals(item.getGuid())) {
                item.setTitle("bad\u0000title");
            }
            return item;
        }).when(normalizer).normalize(any(), any(), any());

        int saved = poller.pollSingleSource(source());

        assertThat(saved).isEqualTo(2);
        assertThat(items.findAll()).hasSize(2);
        assertThat(outboxEvents.findAll()).hasSize(2);
        assertThat(skips.findAll()).singleElement().satisfies(skip -> {
            assertThat(skip.getId().getGuid()).isEqualTo("nul-poison");
            assertThat(skip.getFailureClass()).isEqualTo("NUL_BYTE");
        });
        ca.feedline.domain.FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
        assertThat(stored.getEtag()).isNull();
        assertThat(stored.getLastModified()).isNull();
    }

    @Test
    void cleanPoll_advancesValidatorsAndLeavesNoSkips() {
        etag.set("\"clean-etag\"");
        lastModified.set("Wed, 19 Nov 2025 00:00:00 GMT");
        body.set(feed(entry("clean-1", "Clean one"), entry("clean-2", "Clean two")));

        int saved = poller.pollSingleSource(source());

        assertThat(saved).isEqualTo(2);
        assertThat(skips.count()).isZero();
        ca.feedline.domain.FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
        assertThat(stored.getEtag()).isEqualTo("\"clean-etag\"");
        assertThat(stored.getLastModified()).isEqualTo("Wed, 19 Nov 2025 00:00:00 GMT");
        Instant firstNewItemAt = stored.getLastNewItemAt();
        assertThat(firstNewItemAt).isNotNull();

        poller.pollSingleSource(source());

        assertThat(seenIfNoneMatch.get()).isEqualTo("\"clean-etag\"");
        assertThat(skips.count()).isZero();
        assertThat(sources.findByUrl(feedUrl()).orElseThrow().getLastNewItemAt()).isEqualTo(firstNewItemAt);
    }

    @Test
    void oldSkips_arePrunedByRetentionWindow() {
        ca.feedline.domain.FeedSource source = sources.save(
                new ca.feedline.domain.FeedSource("Retention", "http://retention.test/feed"));
        skips.save(new IngestSkip(new IngestSkipId(source.getId(), "old-guid"),
                "TITLE_TOO_LONG", Instant.now().minus(Duration.ofDays(60))));

        skipRecorder.prune(Duration.ofDays(30));

        assertThat(skips.count()).isZero();
    }

    @Test
    void skipRecorderFailure_doesNotDiscardGoodItems() {
        doThrow(new RuntimeException("skip store unavailable"))
                .when(skipRecorder).record(any(), anyString(), anyString(), any());
        etag.set("\"poison-etag\"");
        body.set(feed(entry("good-1", "Good one"),
                entry("poison-long", "poison-long"),
                entry("good-2", "Good two")));
        doAnswer(invocation -> {
            ca.feedline.domain.Item item = (ca.feedline.domain.Item) invocation.callRealMethod();
            if ("poison-long".equals(item.getGuid())) {
                item.setTitle("y".repeat(1001));
            }
            return item;
        }).when(normalizer).normalize(any(), any(), any());

        int saved = poller.pollSingleSource(source());

        assertThat(saved).isEqualTo(2);
        assertThat(items.findAll()).hasSize(2);
        assertThat(skips.count()).isZero();
    }

    @Test
    void clean200WithoutValidators_clearsStoredValidators() {
        etag.set("\"stale-etag\"");
        lastModified.set("Wed, 19 Nov 2025 00:00:00 GMT");
        body.set(feed(entry("stale-1", "Stale one")));

        assertThat(poller.pollSingleSource(source())).isEqualTo(1);
        ca.feedline.domain.FeedSource first = sources.findByUrl(feedUrl()).orElseThrow();
        assertThat(first.getEtag()).isEqualTo("\"stale-etag\"");
        assertThat(first.getLastModified()).isEqualTo("Wed, 19 Nov 2025 00:00:00 GMT");

        etag.set(null);
        lastModified.set(null);
        body.set(feed(entry("fresh-1", "Fresh one")));

        assertThat(poller.pollSingleSource(source())).isEqualTo(1);
        ca.feedline.domain.FeedSource cleared = sources.findByUrl(feedUrl()).orElseThrow();
        assertThat(cleared.getEtag()).isNull();
        assertThat(cleared.getLastModified()).isNull();
        assertThat(seenIfNoneMatch.get()).isEqualTo("\"stale-etag\"");

        assertThat(poller.pollSingleSource(source())).isZero();
        assertThat(seenIfNoneMatch.get()).isNull();
    }

    @Test
    void samePoisonItemOnNextPoll_upsertsOccurrences() {
        etag.set("\"poison-etag\"");
        body.set(feed(entry("good-1", "Good one"), entry("poison-long", "poison-long")));
        doAnswer(invocation -> {
            ca.feedline.domain.Item item = (ca.feedline.domain.Item) invocation.callRealMethod();
            if ("poison-long".equals(item.getGuid())) {
                item.setTitle("y".repeat(1001));
            }
            return item;
        }).when(normalizer).normalize(any(), any(), any());

        assertThat(poller.pollSingleSource(source())).isEqualTo(1);
        assertThat(poller.pollSingleSource(source())).isZero();

        List<IngestSkip> recorded = skips.findAll();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getId().getGuid()).isEqualTo("poison-long");
        assertThat(recorded.get(0).getFailureClass()).isEqualTo("TITLE_TOO_LONG");
        assertThat(recorded.get(0).getOccurrences()).isEqualTo(2);
        assertThat(recorded.get(0).getLastSkippedAt()).isAfterOrEqualTo(recorded.get(0).getFirstSkippedAt());
        assertThat(sources.findByUrl(feedUrl()).orElseThrow().getEtag()).isNull();
        assertThat(items.count()).isEqualTo(1);
    }

    private void serveFeed(HttpExchange exchange) throws IOException {
        seenIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
        byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
        if (etag.get() != null) {
            exchange.getResponseHeaders().set("ETag", etag.get());
        }
        if (lastModified.get() != null) {
            exchange.getResponseHeaders().set("Last-Modified", lastModified.get());
        }
        exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String feed(String... entries) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<rss version=\"2.0\"><channel><title>Fixture</title>");
        for (String entry : entries) {
            xml.append(entry);
        }
        return xml.append("</channel></rss>").toString();
    }

    private String entry(String guid, String title) {
        return "<item><guid>" + guid + "</guid><link>http://example.com/" + guid
                + "</link><title>" + title + "</title></item>";
    }

    private Source source() {
        Source source = new Source("Isolation Fixture", feedUrl());
        source.setEnabled(true);
        return source;
    }

    private String feedUrl() {
        return "http://localhost:" + port + "/feed";
    }
}
