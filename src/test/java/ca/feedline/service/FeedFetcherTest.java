package ca.feedline.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FeedFetcher} against a real HTTP server, covering:
 * <ul>
 *   <li>the configured User-Agent is sent on the wire</li>
 *   <li>clean 200 responses carry content and validators</li>
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
                3,
                true
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
        String body = "<rss></rss>";
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        String url = server.url("/feed").toString();

        FeedFetchResult result = fetcher.fetch(url, null, null);

        assertAll(
                () -> assertEquals(200, result.httpCode()),
                () -> assertEquals(body, result.content()),
                () -> assertEquals(url, result.usedUrl()),
                () -> assertTrue(result.ok())
        );
        RecordedRequest request = server.takeRequest();
        assertEquals("/feed", request.getPath());
        assertEquals(USER_AGENT_HEADER, request.getHeader("User-Agent"));
    }

    @Test
    void extractsValidatorsFromClean200() throws Exception {
        String etag = "\"final-etag\"";
        String lastModified = "Wed, 19 Nov 2025 00:00:00 GMT";
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("<rss></rss>")
                .setHeader("ETag", etag)
                .setHeader("Last-Modified", lastModified));

        FeedFetchResult result = fetcher.fetch(server.url("/feed").toString(), null, null);

        assertAll(
                () -> assertEquals(200, result.httpCode()),
                () -> assertEquals(etag, result.newEtag()),
                () -> assertEquals(lastModified, result.newLastModified()),
                () -> assertTrue(result.hasNewValidators())
        );
    }

    @Test
    void stopsFollowingRedirectsAtConfiguredLimit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/hop2").toString()));
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/hop3").toString()));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<rss></rss>"));

        FeedFetchResult result = fetcher.fetch(server.url("/start").toString(), null, null);

        assertAll(
                () -> assertEquals(302, result.httpCode(), "the last redirect is returned when the limit is exhausted"),
                () -> assertNull(result.content(), "an exhausted redirect carries no feed body"),
                () -> assertEquals(2, server.getRequestCount(), "initial request plus exactly one allowed redirect")
        );
        assertEquals("/start", server.takeRequest().getPath());
        assertEquals("/hop2", server.takeRequest().getPath());
    }

    @Test
    void followsRedirectWithinConfiguredLimit() throws Exception {
        String body = "<rss></rss>";
        server.enqueue(new MockResponse().setResponseCode(301)
                .setHeader("Location", server.url("/final").toString()));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        FeedFetchResult result = fetcher.fetch(server.url("/start").toString(), null, null);

        assertAll(
                () -> assertEquals(200, result.httpCode()),
                () -> assertEquals(body, result.content()),
                () -> assertEquals(2, server.getRequestCount())
        );
        assertEquals(USER_AGENT_HEADER, server.takeRequest().getHeader("User-Agent"));
        assertEquals("/final", server.takeRequest().getPath());
    }

    @Test
    void rejectsUnsafeInitialUrlsBeforeAnyRequest() {
        FeedFetchResult privateAddress = fetcher.fetch("http://10.0.0.1/feed.xml", null, null);
        FeedFetchResult fileScheme = fetcher.fetch("file:///etc/passwd", null, null);

        assertAll(
                () -> assertEquals(0, privateAddress.httpCode()),
                () -> assertEquals(0, fileScheme.httpCode()),
                () -> assertEquals(0, server.getRequestCount())
        );
    }

    @Test
    void rejectsUnsafeRedirectTargetsWithoutRetrying() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", "http://169.254.169.254/latest/meta-data/"));

        FeedFetchResult result = fetcher.fetch(server.url("/start").toString(), null, null);

        assertAll(
                () -> assertEquals(0, result.httpCode()),
                () -> assertEquals(1, server.getRequestCount()),
                () -> assertEquals(0, sleeper.getTotalSlept())
        );
        assertEquals("/start", server.takeRequest().getPath());
    }

    @Test
    void redirectToRobotsDisallowedOriginIsBlockedBeforeAnyRequest() throws Exception {
        MockWebServer blockedOrigin = new MockWebServer();
        blockedOrigin.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", blockedOrigin.url("/blocked").toString()));
            String blockedOriginKey = FeedFetchConfig.originOf(URI.create(blockedOrigin.url("/").toString()));
            RobotsTxtParser perOrigin = Mockito.mock(RobotsTxtParser.class);
            Mockito.when(perOrigin.getRules(Mockito.anyString())).thenAnswer(invocation ->
                    blockedOriginKey.equals(invocation.getArgument(0))
                            ? RobotsTxtParser.RobotsRules.DENY_ALL
                            : RobotsTxtParser.RobotsRules.ALLOW_ALL);
            FeedFetcher guarded = new FeedFetcher(config, perOrigin, sleeper, clock);

            FeedFetchResult result = guarded.fetch(server.url("/start").toString(), null, null);

            assertAll(
                    () -> assertTrue(result.robotsDisallowed()),
                    () -> assertEquals(1, server.getRequestCount()),
                    () -> assertEquals(0, blockedOrigin.getRequestCount())
            );
        } finally {
            blockedOrigin.shutdown();
        }
    }

    @Test
    void closesResponseBodiesForEveryStatusBranch() throws Exception {
        for (int status : new int[]{200, 304, 404, 500, 503}) {
            CloseTrackingInputStream body = new CloseTrackingInputStream("<rss/>");

            fetcherWithResponse(status, body).fetch(server.url("/feed").toString(), null, null);

            assertTrue(body.closed, "response body should be closed for status " + status);
        }
    }

    private FeedFetcher fetcherWithResponse(int statusCode, InputStream body)
            throws IOException, InterruptedException {
        FeedFetchConfig noRetryConfig = new FeedFetchConfig(
                USER_AGENT_URL,
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                5 * 1024 * 1024,
                1,
                Duration.ZERO,
                0,
                true
        );
        RobotsTxtParser robots = Mockito.mock(RobotsTxtParser.class);
        Mockito.when(robots.getRules(Mockito.anyString()))
                .thenReturn(RobotsTxtParser.RobotsRules.ALLOW_ALL);

        @SuppressWarnings("unchecked")
        HttpResponse<InputStream> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(statusCode);
        Mockito.when(response.body()).thenReturn(body);
        Mockito.when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));

        FeedHttpClient client = Mockito.mock(FeedHttpClient.class);
        Mockito.when(client.send(Mockito.any(HttpRequest.class), Mockito.any(FeedHttpClient.HopGuard.class)))
                .thenReturn(response);

        return new FeedFetcher(noRetryConfig, client, robots, new TestSleeper(), clock);
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInputStream(String body) {
            super(body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
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
