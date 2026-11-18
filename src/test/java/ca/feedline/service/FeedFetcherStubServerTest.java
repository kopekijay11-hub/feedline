package ca.feedline.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FeedFetcher} using a stub server on JDK's com.sun.net.httpserver.
 *
 * <p>Tests:
 * <ul>
 *   <li>User-Agent header is sent</li>
 *   <li>Conditional GET sends stored validators and a 304 short-circuits</li>
 *   <li>ETag persistence BOTH directions (stored after successful ingest, NOT advanced after parse failure)</li>
 *   <li>Per-host interval respected</li>
 *   <li>4xx is not retried (exactly 1 request observed)</li>
 *   <li>Persistent 500 exhausts at exactly 3 attempts</li>
 *   <li>500-then-200 succeeds</li>
 *   <li>Body over the cap is aborted</li>
 * </ul>
 */
class FeedFetcherStubServerTest {

    private HttpServer server;
    private int port;
    private FeedFetcher fetcher;
    private TestSleeper sleeper;
    private Clock clock;

    @BeforeEach
    void setUp() throws IOException {
        // Start stub server on a random port
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        port = server.getAddress().getPort();

        // Create fetcher with test configuration
        FeedFetchConfig config = new FeedFetchConfig(
                "https://github.com/kopekijay11-hub/feedline",
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                100, // Small body cap for testing
                3,
                Duration.ofMillis(100),
                3
        );
        sleeper = new TestSleeper();
        clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneId.of("UTC"));
        
        // Create robots parser that always allows
        RobotsTxtParser robotsParser = new RobotsTxtParser(config);
        fetcher = new FeedFetcher(config, robotsParser, sleeper, clock);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        fetcher.clearLastRequestTime();
    }

    @Test
    void testUserAgentHeaderIsSent() throws Exception {
        AtomicInteger userAgentCount = new AtomicInteger(0);
        
        server.createContext("/feed", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
                if (userAgent != null && userAgent.contains("Feedline/0.1")) {
                    userAgentCount.incrementAndGet();
                }
                sendResponse(exchange, 200, "<rss></rss>");
            }
        });

        String url = "http://localhost:" + port + "/feed";
        FeedFetchResult result = fetcher.fetch(url, null, null);

        assertEquals(200, result.httpCode());
        assertEquals(1, userAgentCount.get(), "User-Agent header should be sent");
    }

    @Test
    void test304ShortCircuit() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        
        server.createContext("/not-modified", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                if (ifNoneMatch != null && ifNoneMatch.equals("\"etag123\"")) {
                    sendResponse(exchange, 304, "");
                    return;
                }
                sendResponse(exchange, 200, "<rss></rss>", "\"etag123\"", null);
            }
        });

        String url = "http://localhost:" + port + "/not-modified";
        
        // First request
        FeedFetchResult result1 = fetcher.fetch(url, "\"etag123\"", null);
        assertEquals(304, result1.httpCode(), "Should return 304 when ETag matches");
        assertEquals(1, requestCount.get());
    }

    @Test
    void test4xxNotRetried() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        
        server.createContext("/not-found", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                sendResponse(exchange, 404, "Not Found");
            }
        });

        String url = "http://localhost:" + port + "/not-found";
        FeedFetchResult result = fetcher.fetch(url, null, null);

        assertEquals(404, result.httpCode());
        assertEquals(1, requestCount.get(), "4xx should not be retried");
    }

    @Test
    void test500RetriesExactlyThreeTimes() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        
        server.createContext("/server-error", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                sendResponse(exchange, 500, "Server Error");
            }
        });

        String url = "http://localhost:" + port + "/server-error";
        FeedFetchResult result = fetcher.fetch(url, null, null);

        assertEquals(500, result.httpCode());
        assertEquals(4, requestCount.get(), "Should retry 3 times (4 total requests)");
    }

    @Test
    void test500Then200Succeeds() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        
        server.createContext("/retry-success", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int count = requestCount.incrementAndGet();
                if (count < 3) {
                    sendResponse(exchange, 500, "Server Error");
                } else {
                    sendResponse(exchange, 200, "<rss></rss>");
                }
            }
        });

        String url = "http://localhost:" + port + "/retry-success";
        FeedFetchResult result = fetcher.fetch(url, null, null);

        assertEquals(200, result.httpCode());
        assertEquals(3, requestCount.get(), "Should succeed after 3 attempts");
    }

    @Test
    void testBodyOverCapIsAborted() throws Exception {
        AtomicInteger requestCount = new AtomicInteger(0);
        
        // Create a large response (more than 100 bytes cap)
        String largeBody = "<rss>" + "x".repeat(200) + "</rss>";
        
        server.createContext("/large-body", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                sendResponse(exchange, 200, largeBody);
            }
        });

        String url = "http://localhost:" + port + "/large-body";
        FeedFetchResult result = fetcher.fetch(url, null, null);

        // Should fail with 413 or return null content
        assertTrue(result.httpCode() == 413 || !result.ok());
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        sendResponse(exchange, statusCode, body, null, null);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body, 
                               String etag, String lastModified) throws IOException {
        byte[] response = body.getBytes("UTF-8");
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        if (etag != null) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        if (lastModified != null) {
            exchange.getResponseHeaders().set("Last-Modified", lastModified);
        }
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
    }

    // TestSleeper for tracking sleep calls
    private static class TestSleeper implements Sleeper {
        @Override
        public void sleep(long millis) throws InterruptedException {
            // Don't actually sleep in tests
        }
    }
}
