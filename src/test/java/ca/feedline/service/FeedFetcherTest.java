package ca.feedline.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests for {@link FeedFetcher} against a real HTTP server, covering:
 * <ul>
 *   <li>the configured User-Agent is sent on the wire</li>
 *   <li>redirects stop at {@code feed.max-redirects}</li>
 * </ul>
 */
class FeedFetcherTest {

    private static final String USER_AGENT_URL = "https://github.com/kopekijay11-hub/feedline";
    private static final String USER_AGENT_HEADER = "Feedline/0.1 (+" + USER_AGENT_URL + ")";

    private MockWebServer server;
    private FeedFetchConfig config;
    private TestSleeper sleeper;
    private Clock clock;
    private FeedFetcher fetcher;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        config = new FeedFetchConfig(
                USER_AGENT_URL,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                5 * 1024 * 1024,
                1,
                Duration.ofMillis(1000),
                3
        );
        sleeper = new TestSleeper();
        clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneId.of("UTC"));
        RobotsTxtParser robotsParser = Mockito.mock(RobotsTxtParser.class);
        Mockito.when(robotsParser.getRules(Mockito.anyString()))
                .thenReturn(RobotsTxtParser.RobotsRules.ALLOW_ALL);

        fetcher = new FeedFetcher(config, robotsParser, sleeper, clock);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
        fetcher.clearLastRequestTime();
    }

    @Test
    void sendsConfiguredUserAgentOnTheWire() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<rss></rss>"));

        FeedFetchResult result = fetcher.fetch(server.url("/feed").toString(), null, null);

        assertEquals(200, result.httpCode());
        RecordedRequest request = server.takeRequest();
        assertEquals("/feed", request.getPath());
        assertEquals(USER_AGENT_HEADER, request.getHeader("User-Agent"));
    }

    @Test
    void stopsFollowingRedirectsAtConfiguredLimit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/hop2").toString()));
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/hop3").toString()));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<rss></rss>"));

        FeedFetchResult result = fetcher.fetch(server.url("/start").toString(), null, null);

        assertEquals(302, result.httpCode(), "the last redirect is returned when the limit is exhausted");
        assertFalse(result.ok());
        assertEquals(2, server.getRequestCount(), "initial request plus exactly one allowed redirect");
        assertEquals("/start", server.takeRequest().getPath());
        assertEquals("/hop2", server.takeRequest().getPath());
    }

    @Test
    void followsRedirectWithinConfiguredLimit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("/final").toString()));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<rss></rss>"));

        FeedFetchResult result = fetcher.fetch(server.url("/start").toString(), null, null);

        assertEquals(200, result.httpCode());
        assertEquals(2, server.getRequestCount());
        assertEquals(USER_AGENT_HEADER, server.takeRequest().getHeader("User-Agent"));
        assertEquals("/final", server.takeRequest().getPath());
    }

    private static class TestSleeper implements Sleeper {
        private final AtomicLong totalSlept = new AtomicLong(0);

        @Override
        public void sleep(long millis) throws InterruptedException {
            totalSlept.addAndGet(millis);
        }

        public long getTotalSlept() {
            return totalSlept.get();
        }
    }
}
