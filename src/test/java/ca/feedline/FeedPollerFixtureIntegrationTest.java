package ca.feedline;

import ca.feedline.config.FeedProperties.Source;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.FeedPollerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests exercising the M2a feed fixtures through the poller.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "feed.scheduling-enabled=false")
class FeedPollerFixtureIntegrationTest {

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
    private FeedPollerService pollerService;

    @Autowired
    private ItemRepository items;

    @Autowired
    private FeedSourceRepository sources;

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        items.deleteAll();
        sources.deleteAll();
        server = HttpServer.create(new InetSocketAddress(0), 0);
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

    @Test
    void xxeFeed_isRejectedSafely_noExternalEntityResolution_nothingIngested() {
        serveFixture("/feed", "/fixtures/xxe-feed.xml");

        Source source = source("/feed", "XXE Fixture");
        int saved = assertDoesNotThrow(() -> pollerService.pollSingleSource(source),
                "XXE feed must not crash the poller");

        assertEquals(0, saved, "Rejected XXE feed must ingest nothing");
        assertEquals(0, items.count(), "No items may be stored from a rejected XXE feed");
    }

    @Test
    void malformedXml_failsPollInIsolation_withoutCrashingPoller() {
        serveFixture("/feed", "/fixtures/malformed-xml.xml");

        Source source = source("/feed", "Malformed Fixture");
        int saved = assertDoesNotThrow(() -> pollerService.pollSingleSource(source),
                "Malformed XML must not crash the poller");

        assertEquals(0, saved, "Malformed XML poll must save no items");
        assertEquals(0, items.count(), "No items may be stored from malformed XML");
    }

    @Test
    void emptyFeed_pollsCleanly_withZeroItems() {
        serveFixture("/feed", "/fixtures/empty-feed.xml");

        Source source = source("/feed", "Empty Fixture");
        int saved = assertDoesNotThrow(() -> pollerService.pollSingleSource(source),
                "Empty feed must not crash the poller");

        assertEquals(0, saved, "Empty feed must save zero items");
        assertEquals(0, items.count(), "Empty feed must leave no items stored");
    }

    @Test
    void atomFeed_ingestsEntriesUsingAtomIdsAsGuids() {
        serveFixture("/feed", "/fixtures/atom-feed.xml");

        Source source = source("/feed", "Atom Fixture");
        int saved = assertDoesNotThrow(() -> pollerService.pollSingleSource(source),
                "Atom feed must poll without crashing");

        assertEquals(2, saved, "Both Atom entries must be ingested");
        assertEquals(2, items.count(), "Atom entries must be stored once each");
        Set<String> guids = items.findAll().stream()
                .map(ca.feedline.domain.Item::getGuid)
                .collect(Collectors.toSet());
        assertEquals(Set.of("urn:feedline:atom:1", "urn:feedline:atom:2"), guids,
                "Atom <id> values must be used as GUIDs");
        assertEquals(Set.of("Atom one", "Atom two"), items.findAll().stream()
                .map(ca.feedline.domain.Item::getTitle)
                .collect(Collectors.toSet()));
    }

    @Test
    void duplicateGuids_ingestsEachGuidOnce() {
        serveFixture("/feed", "/fixtures/duplicate-guids.xml");

        Source source = source("/feed", "Duplicate GUIDs Fixture");
        int saved = assertDoesNotThrow(() -> pollerService.pollSingleSource(source),
                "Duplicate-GUID feed must not crash the poller");

        assertEquals(2, saved, "Only distinct GUIDs may be ingested");
        assertEquals(2, items.count(), "Duplicate GUID entries must not create extra rows");
    }

    private void serveFixture(String path, String resourcePath) {
        String body = readResource(resourcePath);
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
    }

    private Source source(String path, String name) {
        Source source = new Source(name, feedUrl(path));
        source.setEnabled(true);
        return source;
    }

    private String feedUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private String readResource(String resourcePath) {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture: " + resourcePath, e);
        }
    }
    @Test
    void bomPrefixedFeed_ingestsIdenticallyToClean() {
        // Clean variant
        serveFixture("/feed", "/fixtures/dedupe-test.xml");
        Source cleanSource = source("/feed", "Clean Fixture");
        int cleanSaved = assertDoesNotThrow(() -> pollerService.pollSingleSource(cleanSource));
        List<String> cleanGuids = new ArrayList<>();
        items.findAll().forEach(i -> cleanGuids.add(i.getGuid()));
        items.deleteAll();
        sources.deleteAll();

        // BOM-prefixed variant served manually
        String body = readResource("/fixtures/dedupe-test.xml");
        String bomBody = "\uFEFF" + body;
        server.createContext("/bom", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = bomBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });
        Source bomSource = source("/bom", "BOM Fixture");
        int bomSaved = assertDoesNotThrow(() -> pollerService.pollSingleSource(bomSource));
        List<String> bomGuids = new ArrayList<>();
        items.findAll().forEach(i -> bomGuids.add(i.getGuid()));

        assertEquals(cleanSaved, bomSaved, "BOM-prefixed feed must ingest same item count");
        Set<String> cleanSet = new TreeSet<>(cleanGuids);
        Set<String> bomSet = new TreeSet<>(bomGuids);
        assertEquals(cleanSet, bomSet, "BOM-prefixed feed must yield same GUIDs");
    }


    @Test
    void validatorsNotPersistedOnParseFailure() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seenIfNoneMatch = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/bad", exchange -> {
            seenIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            String body = "<not-xml";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
            exchange.getResponseHeaders().set("ETag", "\"abc123\"");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        Source src = source("/bad", "Bad Parse Fixture");

        // First poll: fetch 200 + ETag + unparseable body -> parse fails, no validators stored
        int saved1 = assertDoesNotThrow(() -> pollerService.pollSingleSource(src));
        assertEquals(0, saved1);

        // Second poll: request must carry NO If-None-Match
        seenIfNoneMatch.set("UNSET");
        int saved2 = assertDoesNotThrow(() -> pollerService.pollSingleSource(src));
        assertEquals(0, saved2);
        assertNull(seenIfNoneMatch.get(), "Second request must not carry If-None-Match after a failed parse");
    }

}
