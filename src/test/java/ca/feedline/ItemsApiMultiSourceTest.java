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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ItemsApiMultiSourceTest {

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
        registry.add("feed.sources[0].name", () -> "Multi Source A");
        registry.add("feed.sources[0].url", () -> "http://localhost:9/a");
        registry.add("feed.sources[0].enabled", () -> "true");
        registry.add("feed.sources[1].name", () -> "Multi Source B");
        registry.add("feed.sources[1].url", () -> "http://localhost:9/b");
        registry.add("feed.sources[1].enabled", () -> "true");
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

    private FeedSource sourceA;
    private FeedSource sourceB;

    @BeforeEach
    void setUp() {
        items.deleteAll();
        sources.deleteAll();
        initializer.run(null);
        sourceA = sources.findAll().stream().filter(s -> s.getName().equals("Multi Source A")).findFirst().orElseThrow();
        sourceB = sources.findAll().stream().filter(s -> s.getName().equals("Multi Source B")).findFirst().orElseThrow();
    }

    @AfterEach
    void tearDown() {
        items.deleteAll();
        sources.deleteAll();
    }

    private JsonNode getJson(String path) throws Exception {
        return mapper.readTree(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void multiSource_returnsUnionAndTotalEqualsSum() throws Exception {
        for (int i = 0; i < 3; i++) {
            items.save(new Item(sourceA, "ga-" + i, "http://x/a/" + i, "A item " + i, "summary",
                    Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i)));
        }
        for (int i = 0; i < 5; i++) {
            items.save(new Item(sourceB, "gb-" + i, "http://x/b/" + i, "B item " + i, "summary",
                    Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z").plusSeconds(10 + i)));
        }

        JsonNode multi = getJson("/api/v1/items?sources=" + sourceA.getId() + "," + sourceB.getId() + "&size=100");
        JsonNode onlyA = getJson("/api/v1/items?source=" + sourceA.getId() + "&size=100");
        JsonNode onlyB = getJson("/api/v1/items?source=" + sourceB.getId() + "&size=100");

        assertThat(multi.get("total").asLong()).isEqualTo(8);
        assertThat(onlyA.get("total").asLong()).isEqualTo(3);
        assertThat(onlyB.get("total").asLong()).isEqualTo(5);
        assertThat(multi.get("total").asLong()).isEqualTo(onlyA.get("total").asLong() + onlyB.get("total").asLong());
        assertThat(multi.get("items")).hasSize(8);
    }

    @Test
    void multiSource_pagingNoDuplicatesOrGaps() throws Exception {
        int total = 13;
        for (int i = 0; i < total; i++) {
            FeedSource src = (i % 2 == 0) ? sourceA : sourceB;
            items.save(new Item(src, "gp-" + i, "http://x/p/" + i, "P item " + i, "summary",
                    Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i)));
        }

        int size = 4;
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String url = "/api/v1/items?sources=" + sourceA.getId() + "," + sourceB.getId()
                    + "&size=" + size + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = getJson(url);
            assertThat(page.get("total").asLong()).isEqualTo(total);
            for (JsonNode it : page.get("items")) {
                assertThat(seen.add(it.get("id").asLong())).isTrue();
            }
            pages++;
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.get("nextCursor").isNull()).isTrue();
                break;
            }
            assertThat(page.get("nextCursor").asText()).isNotBlank();
            cursor = page.get("nextCursor").asText();
            assertThat(pages).isLessThan(100);
        }
        assertThat(seen).hasSize(total);
    }

    @Test
    void multiSource_combinedWithQAndFetchedRange() throws Exception {
        Instant t1 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2025-01-02T00:00:00Z");
        Instant t3 = Instant.parse("2025-01-03T00:00:00Z");

        items.save(new Item(sourceA, "gq-1", "http://x/q/1", "needle alpha", "summary", null, t1));
        items.save(new Item(sourceB, "gq-2", "http://x/q/2", "needle beta", "summary", null, t2));
        items.save(new Item(sourceA, "gq-3", "http://x/q/3", "other gamma", "summary", null, t2));
        items.save(new Item(sourceB, "gq-4", "http://x/q/4", "needle delta", "summary", null, t3));

        JsonNode res = getJson("/api/v1/items?sources=" + sourceA.getId() + "," + sourceB.getId()
                + "&q=needle&fetchedFrom=2025-01-02T00:00:00Z&fetchedTo=2025-01-02T23:59:59Z&size=100");
        assertThat(res.get("total").asLong()).isEqualTo(1);
        assertThat(res.get("items")).hasSize(1);
        assertThat(res.get("items").get(0).get("title").asText()).isEqualTo("needle beta");
    }

    @Test
    void legacySource_returnsOnlyThatSource() throws Exception {
        for (int i = 0; i < 2; i++) {
            items.save(new Item(sourceA, "gl-a-" + i, "http://x/la/" + i, "A" + i, "summary", null,
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i)));
        }
        for (int i = 0; i < 3; i++) {
            items.save(new Item(sourceB, "gl-b-" + i, "http://x/lb/" + i, "B" + i, "summary", null,
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(10 + i)));
        }

        JsonNode res = getJson("/api/v1/items?source=" + sourceA.getId() + "&size=100");
        assertThat(res.get("total").asLong()).isEqualTo(2);
        assertThat(res.get("items")).hasSize(2);
        for (JsonNode it : res.get("items")) {
            assertThat(it.get("sourceId").asLong()).isEqualTo(sourceA.getId());
        }
    }

    @Test
    void sourceAndSourcesTogether_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/items?source=" + sourceA.getId()
                + "&sources=" + sourceA.getId() + "," + sourceB.getId()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sourcesNonNumeric_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/items?sources=abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sourcesMoreThan50_returns400() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 51; i++) {
            if (i > 1) sb.append(',');
            sb.append(i);
        }
        mockMvc.perform(get("/api/v1/items?sources=" + sb))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptySources_behavesAsNoFilter() throws Exception {
        for (int i = 0; i < 2; i++) {
            items.save(new Item(sourceA, "ge-a-" + i, "http://x/ea/" + i, "A" + i, "summary", null,
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i)));
        }
        for (int i = 0; i < 3; i++) {
            items.save(new Item(sourceB, "ge-b-" + i, "http://x/eb/" + i, "B" + i, "summary", null,
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(10 + i)));
        }

        JsonNode res = getJson("/api/v1/items?sources=&size=100");
        assertThat(res.get("total").asLong()).isEqualTo(5);
        assertThat(res.get("items")).hasSize(5);
    }

    @Test
    void unknownSources_matchNothing() throws Exception {
        items.save(new Item(sourceA, "gu-1", "http://x/u/1", "U", "summary", null,
                Instant.parse("2025-01-01T00:00:00Z")));

        JsonNode res = getJson("/api/v1/items?sources=999999,999998&size=100");
        assertThat(res.get("total").asLong()).isEqualTo(0);
        assertThat(res.get("items")).isEmpty();
    }

    @Test
    void strictBinding_sourcesAccepted_bogusRejected() throws Exception {
        mockMvc.perform(get("/api/v1/items?sources=" + sourceA.getId()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/items?serach=foo"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    String ct = result.getResponse().getContentType();
                    assertThat(ct).contains("problem+json");
                    assertThat(result.getResponse().getContentAsString()).contains("serach");
                });
    }
}
