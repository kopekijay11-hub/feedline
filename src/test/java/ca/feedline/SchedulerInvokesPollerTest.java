package ca.feedline;

import ca.feedline.service.FeedFetchResult;
import ca.feedline.service.FeedFetcher;
import ca.feedline.service.ItemQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.repository.ItemRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import static org.mockito.Mockito.when;
import ca.feedline.domain.FeedSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "feed.poll-delay=200"
})
class SchedulerInvokesPollerTest {

    @MockBean
    private FeedFetcher feedFetcher;
    @MockBean
    private FeedSourceRepository feedSources;
    @MockBean
    private ItemRepository items;
    @MockBean
    private IngestSkipRepository ingestSkips;
    @MockBean
    private ItemQueryService itemQueryService;

    @Autowired
    private ca.feedline.service.FeedPollerService poller;

    private static final AtomicInteger FETCH_COUNT = new AtomicInteger(0);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("feed.sources[0].name", () -> "Test Source");
        r.add("feed.sources[0].url", () -> "http://localhost/test/feed.xml");
        r.add("feed.sources[0].enabled", () -> "true");
    }

    @Test
    void schedulerInvokesPollerWithoutDirectCall() throws InterruptedException {
        FETCH_COUNT.set(0);
        when(feedSources.findByUrl(anyString())).thenReturn(Optional.empty());
        when(feedSources.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(feedFetcher.fetch(anyString(), any(), any())).thenAnswer(inv -> {
            FETCH_COUNT.incrementAndGet();
            return FeedFetchResult.failure(500, "http://localhost/test/feed.xml");
        });

        long deadline = System.currentTimeMillis() + 10000;
        while (FETCH_COUNT.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(FETCH_COUNT.get()).isGreaterThan(0);
    }
}
