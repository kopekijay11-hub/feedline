package ca.feedline.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobotsTxtParserTest {

    private static final String USER_AGENT = "Feedline/0.1 (+https://github.com/kopekijay11-hub/feedline)";

    private MockWebServer server;
    private RobotsTxtParser parser;
    private MutableClock clock;
    private String origin;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        origin = "http://localhost:" + server.getPort();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        parser = new RobotsTxtParser(config(), clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void allowsWhenRobotsTxt200PermitsPath() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("User-agent: *\nAllow: /\n"));

        assertTrue(parser.isAllowed(origin, "/feed"));

        RecordedRequest request = server.takeRequest();
        assertEquals("/robots.txt", request.getPath());
        assertEquals(USER_AGENT, request.getHeader("User-Agent"));
    }

    @Test
    void disallowsWhenRobotsTxt200ForbidsPath() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("User-agent: *\nDisallow: /\n"));

        assertFalse(parser.isAllowed(origin, "/feed"));
    }

    @Test
    void failsClosedOn403AndDoesNotCacheIt() {
        server.enqueue(new MockResponse().setResponseCode(403).setBody("Forbidden"));
        assertFalse(parser.isAllowed(origin, "/feed"), "403 must disallow");

        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("User-agent: *\nAllow: /\n"));
        assertTrue(parser.isAllowed(origin, "/feed"), "a later successful fetch must be re-evaluated");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void failsClosedOn500AndDoesNotCacheIt() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("Server Error"));
        assertFalse(parser.isAllowed(origin, "/feed"), "5xx must disallow");

        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("User-agent: *\nAllow: /\n"));
        assertTrue(parser.isAllowed(origin, "/feed"), "a later successful fetch must be re-evaluated");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void allowsAndCachesWhenRobotsTxtIs404() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));

        assertTrue(parser.isAllowed(origin, "/feed"));
        assertTrue(parser.isAllowed(origin, "/other"), "404 allow-all is cached");
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void reEvaluatesCachedRulesAfter24Hours() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("User-agent: *\nDisallow: /\n"));

        assertFalse(parser.isAllowed(origin, "/feed"));
        clock.advance(Duration.ofMinutes(59));
        assertFalse(parser.isAllowed(origin, "/feed"), "rules stay cached before TTL expiry");
        assertEquals(1, server.getRequestCount());

        clock.advance(Duration.ofMinutes(1));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("User-agent: *\nAllow: /\n"));

        assertTrue(parser.isAllowed(origin, "/feed"), "rules are re-fetched at 24 hours");
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void failsClosedOnNetworkError() {
        assertFalse(parser.isAllowed("http://localhost:1", "/feed"));
    }

    @Test
    void fixtureRules_longestMatchWinsAndNonGenericAgentIsIgnored() throws IOException {
        RobotsTxtParser.RobotsRules rules = parser.parse(readResource("/fixtures/robots-rules.txt"));

        assertTrue(rules.isAllowed("/feed"));
        assertFalse(rules.isAllowed("/blocked"));
        assertTrue(rules.isAllowed("/public/feed"), "longer Allow rule wins for generic agent");
        assertFalse(rules.isAllowed("/public/private/feed"), "longest Disallow rule wins");
        assertTrue(rules.isAllowed("/private-for-badbot"), "BadBot-only rules must not apply to generic agent");
    }

    private String readResource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Fixture not found: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static FeedFetchConfig config() {
        return new FeedFetchConfig(
                "https://github.com/kopekijay11-hub/feedline",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                5 * 1024 * 1024,
                1,
                Duration.ZERO,
                0,
                true
        );
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone = ZoneId.of("UTC");

        private MutableClock(Instant start) {
            this.instant = new AtomicReference<>(start);
        }

        private void advance(Duration delta) {
            instant.updateAndGet(current -> current.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
