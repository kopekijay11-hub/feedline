package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.StoryClusterMemberRepository;
import ca.feedline.repository.StoryClusterRepository;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoryClusterPassLoggingTest {

    @Test
    void scheduledPassLogsStartAndCompletionWithDuration() {
        Logger logger = (Logger) LoggerFactory.getLogger(StoryClusterService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            ItemRepository items = Mockito.mock(ItemRepository.class);
            StoryClusterRepository clusters = Mockito.mock(StoryClusterRepository.class);
            StoryClusterMemberRepository members = Mockito.mock(StoryClusterMemberRepository.class);
            Instant now = Instant.parse("2026-11-25T00:00:00Z");
            FeedSource a = new FeedSource("Alpha", "http://example/alpha");
            FeedSource b = new FeedSource("Beta", "http://example/beta");
            Item first = new Item(a, "g-a", "http://example/g-a", "alpha beta gamma delta", "summary", now, now);
            first.setId(1L);
            Item second = new Item(b, "g-b", "http://example/g-b", "alpha beta gamma epsilon", "summary", now, now);
            second.setId(2L);
            Mockito.when(items.findAll()).thenReturn(List.of(first, second));
            Mockito.when(clusters.findAll()).thenReturn(List.of());

            StoryClusterService service = new StoryClusterService(items, clusters, members, new FeedProperties(), null);
            service.scheduledPass();

            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("Story cluster pass started");
            assertThat(appender.list.get(1).getFormattedMessage())
                    .matches("Story cluster pass completed in \\d+ ms; items considered: 2");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
