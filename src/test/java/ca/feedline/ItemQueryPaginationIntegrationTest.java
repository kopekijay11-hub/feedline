package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.ItemQueryService;
import ca.feedline.dto.ItemsPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "feed.scheduling-enabled=false")
class ItemQueryPaginationIntegrationTest {

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
    }

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private ItemQueryService queryService;

    @BeforeEach
    void clean() {
        items.deleteAll();
        sources.deleteAll();
    }

    private FeedSource source() {
        FeedSource s = new FeedSource("src", "https://example.test/feed.xml");
        return sources.save(s);
    }

    private List<Long> walk(int total, int pageSize) {
        FeedSource s = source();
        Instant fetchedAt = Instant.parse("2025-06-01T00:00:00Z");
        for (int i = 0; i < total; i++) {
            items.save(new Item(s, "guid-" + i, "https://example.test/" + i, "title-" + i, "summary", null, fetchedAt));
        }
        List<Long> collected = new ArrayList<>();
        String cursor = null;
        int steps = 0;
        while (true) {
            ItemsPage page = queryService.list(s.getId(), null, null, null, null, null, cursor, pageSize);
            assertThat(page.total()).isEqualTo(total);
            for (var dto : page.items()) {
                collected.add(dto.id());
            }
            steps++;
            if (!page.hasMore()) {
                assertThat(page.nextCursor()).isNull();
                break;
            }
            assertThat(page.nextCursor()).isNotNull();
            cursor = page.nextCursor();
            if (steps > total + 5) {
                throw new IllegalStateException("pagination did not terminate");
            }
        }
        return collected;
    }

    @Test
    void walksSharedFetchedAtBatchToExhaustion() {
        int total = 7;
        int pageSize = 3;
        List<Long> collected = walk(total, pageSize);
        assertThat(collected).hasSize(total);
        assertThat(new HashSet<>(collected)).hasSize(total);
    }

    @Test
    void cursorAtExactBatchBoundary() {
        int total = 6;
        int pageSize = 3;
        List<Long> collected = walk(total, pageSize);
        assertThat(collected).hasSize(total);
        assertThat(new HashSet<>(collected)).hasSize(total);
    }

    @Test
    void singleItemFinalPage() {
        int total = 4;
        int pageSize = 3;
        List<Long> collected = walk(total, pageSize);
        assertThat(collected).hasSize(total);
        assertThat(new HashSet<>(collected)).hasSize(total);
    }
}
