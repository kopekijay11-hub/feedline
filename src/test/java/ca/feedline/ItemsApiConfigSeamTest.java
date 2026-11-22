package ca.feedline;

import ca.feedline.config.SourceRegistryInitializer;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ItemsApiConfigSeamTest {

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
        registry.add("feed.poll-delay", () -> "86400000");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.items.default-page-size", () -> "5");
        registry.add("feed.items.max-page-size", () -> "10");
        registry.add("feed.items.search-language", () -> "simple");
        registry.add("feed.sources[0].name", () -> "Config Source");
        registry.add("feed.sources[0].url", () -> "http://localhost:9/feed");
        registry.add("feed.sources[0].enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private SourceRegistryInitializer initializer;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        items.deleteAll();
        sources.deleteAll();
        initializer.run(null);
    }

    @AfterEach
    void tearDown() {
        items.deleteAll();
        sources.deleteAll();
    }

    private JsonNode getJson(String path) throws Exception {
        MvcResult res = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    @Test
    void usesConfiguredDefaultAndMaxPageSize() throws Exception {
        FeedSource src = sources.findAll().get(0);
        for (int i = 0; i < 12; i++) {
            items.save(new Item(src, "g-cfg-" + i, "http://x/cfg/" + i, "Item " + i, "summary",
                    Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i)));
        }

        JsonNode defaults = getJson("/api/v1/items");
        assertThat(defaults.get("items")).hasSize(5);
        assertThat(defaults.get("total").asLong()).isEqualTo(12);
        assertThat(defaults.get("hasMore").asBoolean()).isTrue();

        JsonNode capped = getJson("/api/v1/items?size=1000");
        assertThat(capped.get("items")).hasSize(10);
        assertThat(capped.get("hasMore").asBoolean()).isTrue();

        JsonNode nonPositive = getJson("/api/v1/items?size=0");
        assertThat(nonPositive.get("items")).hasSize(5);
    }

    @Test
    void usesConfiguredSearchLanguage() throws Exception {
        FeedSource src = sources.findAll().get(0);
        items.save(new Item(src, "g-lang-1", "http://x/lang/1", "run", "summary",
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z")));

        JsonNode res = getJson("/api/v1/items?q=running&size=10");
        assertThat(res.get("total").asLong()).isZero();
        assertThat(res.get("items")).isEmpty();
    }
}
