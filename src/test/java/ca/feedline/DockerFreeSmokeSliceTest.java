package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.FeedPollerService;
import ca.feedline.service.IngestSkipRecorder;
import ca.feedline.service.ItemIngestService;
import ca.feedline.service.ItemQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "feed.scheduling-enabled=false",
        "feed.outbox.enabled=false",
        "feed.cluster.enabled=false",
        "feed.outbox.cleanup.enabled=false",
        "feed.poll-delay=86400000",
        "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
@Import(DockerFreeSmokeSliceTest.Stubs.class)
class DockerFreeSmokeSliceTest {

    @MockBean
    private FeedSourceRepository feedSources;
    @MockBean
    private ItemRepository items;
    @MockBean
    private OutboxEventRepository outboxEvents;
    @MockBean
    private IngestSkipRepository ingestSkips;
    @MockBean
    private ItemIngestService itemIngest;
    @MockBean
    private IngestSkipRecorder skipRecorder;
    @MockBean
    private ItemQueryService itemQueryService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeedPollerService feedPollerService;

    @Test
    void contextBootsWithoutDockerAndHealthResponds() throws Exception {
        assertThat(feedPollerService).isNotNull();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @TestConfiguration
    static class Stubs {
        @Bean
        PlatformTransactionManager stubTransactionManager() {
            PlatformTransactionManager manager = Mockito.mock(PlatformTransactionManager.class);
            Mockito.when(manager.getTransaction(Mockito.any())).thenReturn(new SimpleTransactionStatus());
            return manager;
        }
    }
}
