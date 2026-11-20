package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.FeedFetchResult;
import ca.feedline.service.FeedFetcher;
import ca.feedline.service.ItemQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "feed.poll-delay=200",
        "management.endpoint.health.group.readiness.include=readinessState"
})
@Import(SchedulerInvokesPollerTest.Stubs.class)
class SchedulerInvokesPollerTest {

    private static final String URL = "http://localhost/test/feed.xml";
    private static final AtomicInteger FETCH_COUNT = new AtomicInteger(0);

    @MockBean
    private ItemRepository items;
    @MockBean
    private OutboxEventRepository outboxEvents;
    @MockBean
    private IngestSkipRepository ingestSkips;
    @MockBean
    private ItemQueryService itemQueryService;

    @Autowired
    private ca.feedline.service.FeedPollerService poller;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("feed.sources[0].name", () -> "Test Source");
        registry.add("feed.sources[0].url", () -> URL);
        registry.add("feed.sources[0].enabled", () -> "true");
        registry.add("feed.ssrf-allow-loopback", () -> "true");
    }

    @Test
    void schedulerInvokesPollerWithoutDirectCall() throws InterruptedException {
        FETCH_COUNT.set(0);

        long deadline = System.currentTimeMillis() + 10000;
        while (FETCH_COUNT.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(FETCH_COUNT.get()).isGreaterThan(0);
    }

    /**
     * These beans are created before the scheduler starts and are not reset by
     * Mockito's test listener, so early cycles never see unstubbed seams.
     */
    @TestConfiguration
    static class Stubs {

        @Bean
        PlatformTransactionManager stubTransactionManager() {
            PlatformTransactionManager manager = Mockito.mock(PlatformTransactionManager.class);
            Mockito.when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            return manager;
        }

        @Bean
        @Primary
        FeedFetcher stubFetcher() {
            FeedFetcher fetcher = Mockito.mock(FeedFetcher.class);
            Mockito.when(fetcher.fetch(anyString(), any(), any())).thenAnswer(invocation -> {
                FETCH_COUNT.incrementAndGet();
                return FeedFetchResult.failure(500, URL);
            });
            return fetcher;
        }

        @Bean
        @Primary
        FeedSourceRepository stubFeedSources() {
            FeedSource source = new FeedSource("Test Source", URL);
            source.setId(1L);
            source.setEnabled(true);
            FeedSourceRepository repository = Mockito.mock(FeedSourceRepository.class);
            Mockito.when(repository.findByUrl(anyString())).thenReturn(Optional.of(source));
            Mockito.when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            return repository;
        }
    }
}
