package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedPollerServiceTest {

    private static final String URL = "http://example.test/feed.xml";
    private static final String OLD_ETAG = "\"old-etag\"";
    private static final String OLD_LAST_MODIFIED = "Wed, 19 Nov 2025 00:00:00 GMT";

    @Mock
    private FeedFetcher fetcher;
    @Mock
    private FeedNormalizer normalizer;
    @Mock
    private FeedSourceRepository sources;
    @Mock
    private ItemIngestService itemIngest;
    @Mock
    private IngestSkipRecorder skipRecorder;

    private FeedPollerService poller;

    @BeforeEach
    void setUp() {
        poller = new FeedPollerService(new FeedProperties(), fetcher, normalizer, sources, itemIngest, skipRecorder, null, null);
    }

    @Test
    void robotsDisallowed_recordsRobotsFailureWithoutIngestingOrChangingValidators() {
        FeedSource stored = storedSource();
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED)).thenReturn(FeedFetchResult.robotsDisallowed(URL));

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        assertThat(stored.getLastErrorClass()).isEqualTo("ROBOTS");
        assertThat(stored.getConsecutiveFailures()).isEqualTo(3);
        assertThat(stored.getEtag()).isEqualTo(OLD_ETAG);
        assertThat(stored.getLastModified()).isEqualTo(OLD_LAST_MODIFIED);
        verify(fetcher).fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED);
        verify(sources, atLeastOnce()).save(stored);
        verifyNoInteractions(itemIngest, skipRecorder);
    }

    @Test
    void clean200WithoutValidators_clearsAndKeepsValidatorsClearedOnNextPoll() {
        FeedSource stored = storedSource();
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED)).thenReturn(clean200());
        when(fetcher.fetch(URL, null, null)).thenReturn(clean200());
        when(itemIngest.ingest(eq(stored), any(), any())).thenReturn(new ItemIngestResult("guid-1", true));

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isEqualTo(1);
        assertThat(stored.getEtag()).isNull();
        assertThat(stored.getLastModified()).isNull();

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isEqualTo(1);
        assertThat(stored.getEtag()).isNull();
        assertThat(stored.getLastModified()).isNull();
        verify(fetcher).fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED);
        verify(fetcher).fetch(URL, null, null);
    }

    @Test
    void activeLease_skipsPollingAndDoesNotFetch() {
        FeedSource stored = storedSource();
        stored.setLeaseOwner("other-worker");
        stored.setLeaseUntil(Instant.now().plusSeconds(600));
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        verify(fetcher, never()).fetch(anyString(), any(), any());
    }

    @Test
    void expiredLease_isReclaimedAndPolled() {
        FeedSource stored = storedSource();
        stored.setLeaseOwner("crashed-node");
        stored.setLeaseUntil(Instant.now().minusSeconds(10));
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sources.releaseLease(anyLong())).thenReturn(1);
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED)).thenReturn(notModified());

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        verify(fetcher).fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED);
        verify(sources).releaseLease(stored.getId());
    }

    @Test
    void leaseIsReleasedAfterProcessing() {
        FeedSource stored = storedSource();
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sources.releaseLease(anyLong())).thenReturn(1);
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED)).thenReturn(notModified());

        poller.pollSingleSource(new Source("Fixture", URL));

        verify(sources).releaseLease(stored.getId());
    }

    @Test
    void failureRecordsConsecutiveCountLastErrorAndGrowingBackoff() {
        FeedSource stored = storedSource();
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED)).thenReturn(FeedFetchResult.failure(500, URL));

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        Instant firstBackoff = stored.getNextPollAt();
        assertThat(stored.getConsecutiveFailures()).isEqualTo(3);
        assertThat(stored.getLastErrorClass()).isEqualTo("HTTP_500");
        assertThat(stored.getLastError()).isEqualTo("HTTP 500");
        assertThat(stored.getLastErrorAt()).isNotNull();
        assertThat(firstBackoff).isAfter(Instant.now());

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        assertThat(stored.getConsecutiveFailures()).isEqualTo(4);
        assertThat(stored.getNextPollAt()).isAfter(firstBackoff);
    }

    @Test
    void rateLimitedResponseHonoursRetryAfterAndRecordsRateLimitedOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeedlineMetrics metrics = new FeedlineMetrics(registry);
        FeedPollerService rateLimitedPoller = new FeedPollerService(
                new FeedProperties(), fetcher, normalizer, sources, itemIngest, skipRecorder, metrics, null);
        FeedSource stored = storedSource();
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED))
                .thenReturn(FeedFetchResult.failure(429, URL, Duration.ofSeconds(120)));

        assertThat(rateLimitedPoller.pollSingleSource(new Source("Fixture", URL))).isZero();

        assertThat(stored.getLastErrorClass()).isEqualTo("HTTP_429");
        assertThat(stored.getNextPollAt()).isAfter(Instant.now().plusSeconds(119));
        assertThat(registry.counter("feedline.poller.poll.total", "outcome", "rate_limited").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("feedline.fetch.requests.total", "outcome", "rate_limited").count())
                .isZero();
    }

    @Test
    void cleanPollResetsConsecutiveFailuresBackoffAndErrorState() {
        FeedSource stored = storedSource();
        Instant now = Instant.now();
        stored.setLastErrorClass("HTTP_500");
        stored.setLastErrorAt(now.minusSeconds(60));
        stored.setLastError("HTTP 500");
        stored.setNextPollAt(now.plusSeconds(60));
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED)).thenReturn(notModified());

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        assertThat(stored.getConsecutiveFailures()).isZero();
        assertThat(stored.getLastErrorClass()).isNull();
        assertThat(stored.getLastError()).isNull();
        assertThat(stored.getLastErrorAt()).isNull();
        assertThat(stored.getNextPollAt()).isNull();
        assertThat(stored.getLastSuccessAt()).isNotNull();
    }

    @Test
    void shutdownStopsClaimingAndReleasesOwnedLeases() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FeedlineMetrics metrics = new FeedlineMetrics(registry);
        FeedPollerService shutdownPoller = new FeedPollerService(
                new FeedProperties(), fetcher, normalizer, sources, itemIngest, skipRecorder, metrics, "worker-shutdown", null);
        when(sources.releaseAllLeasesOwnedBy("worker-shutdown")).thenReturn(2);

        shutdownPoller.shutdown();

        assertThat(shutdownPoller.isShuttingDown()).isTrue();
        verify(sources).releaseAllLeasesOwnedBy("worker-shutdown");
        assertThat(registry.counter("feedline.poller.leases.released.total").count()).isEqualTo(2.0);
        assertThat(registry.counter("feedline.shutdown.total", "outcome", "lease_released").count()).isEqualTo(1.0);
        assertThat(shutdownPoller.pollSingleSource(new Source("Fixture", URL))).isZero();
        verify(fetcher, never()).fetch(anyString(), any(), any());
    }

    private FeedFetchResult notModified() {
        return FeedFetchResult.notModified(URL);
    }

    private FeedSource storedSource() {
        FeedSource source = new FeedSource("Fixture", URL);
        source.setId(1L);
        source.setEtag(OLD_ETAG);
        source.setLastModified(OLD_LAST_MODIFIED);
        source.setConsecutiveFailures(2);
        return source;
    }

    @Test
    void soft404HtmlErrorPage_recordsFailureWithoutAdvancingValidatorsOrStoringItems() {
        FeedSource stored = storedSource();
        when(sources.findByUrl(URL)).thenReturn(Optional.of(stored));
        when(sources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fetcher.fetch(URL, OLD_ETAG, OLD_LAST_MODIFIED))
                .thenReturn(new FeedFetchResult(200,
                        "<html><head><title>404 Not Found</title></head><body>Page not found</body></html>",
                        URL, "\"new-etag\"", "Wed, 20 Nov 2025 00:00:00 GMT"));

        assertThat(poller.pollSingleSource(new Source("Fixture", URL))).isZero();

        assertThat(stored.getLastErrorClass()).isEqualTo("PARSE");
        assertThat(stored.getEtag()).isEqualTo(OLD_ETAG);
        assertThat(stored.getLastModified()).isEqualTo(OLD_LAST_MODIFIED);
        verifyNoInteractions(itemIngest, skipRecorder);
    }

    private FeedFetchResult clean200() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<rss version=\"2.0\"><channel><title>Fixture</title>"
                + "<link>http://example.test</link><description>Fixture</description>"
                + "<item><guid>guid-1</guid><link>http://example.test/1</link>"
                + "<title>One</title><description>One</description></item>"
                + "</channel></rss>";
        return new FeedFetchResult(200, xml, URL, null, null);
    }
}
