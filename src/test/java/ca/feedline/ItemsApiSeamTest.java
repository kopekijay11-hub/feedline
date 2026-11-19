package ca.feedline;

import ca.feedline.config.SourceRegistryInitializer;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.FeedPollerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ItemsApiSeamTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.16-alpine")
            .withDatabaseName("feedline")
            .withUsername("feedline")
            .withPassword("feedline");

    static final AtomicReference<String> mode = new AtomicReference<>("ok");
    static final HttpServer server;
    static final int port;
    static {
        try {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.start();
            port = server.getAddress().getPort();
            server.createContext("/feed", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String body = readResourceStatic("/fixtures/bbc-world-rss.xml");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("feed.poll-delay", () -> "86400000");
        registry.add("feed.scheduling.enabled", () -> "false");
        registry.add("feed.sources[0].name", () -> "Seam Source");
        registry.add("feed.sources[0].url", () -> "http://localhost:" + port + "/feed");
        registry.add("feed.sources[0].enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeedPollerService poller;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private SourceRegistryInitializer initializer;

    private final ObjectMapper mapper = new ObjectMapper();

    private static String readResourceStatic(String path) {
        try (InputStream is = ItemsApiSeamTest.class.getResourceAsStream(path)) {
            if (is == null) throw new IllegalArgumentException("missing " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

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
    void canary_searchAndSourceFilter() throws Exception {
        poller.pollOnce();
        JsonNode search = getJson("/api/v1/items?q=BBC&size=100");
        assertThat(search.get("items").size()).isGreaterThan(0);
        long total = search.get("total").asLong();
        assertThat(total).isGreaterThan(0);

        Long sourceId = sources.findAll().get(0).getId();
        JsonNode bySource = getJson("/api/v1/items?source=" + sourceId + "&size=100");
        assertThat(bySource.get("total").asLong()).isGreaterThan(0);
        for (JsonNode it : bySource.get("items")) {
            assertThat(it.get("sourceId").asLong()).isEqualTo(sourceId);
        }
    }

    @Test
    void strictBinding_bogusParam400() throws Exception {
        mockMvc.perform(get("/api/v1/items?serach=foo"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> {
                    String ct = result.getResponse().getContentType();
                    assertThat(ct).contains("problem+json");
                    assertThat(result.getResponse().getContentAsString()).contains("serach");
                });
    }

    @Test
    void pagination_boundaryAndWalk() throws Exception {
        poller.pollOnce();
        long total = getJson("/api/v1/items?size=1").get("total").asLong();
        assertThat(total).isGreaterThan(5);

        // walk with size 3
        int size = 3;
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String url = "/api/v1/items?size=" + size + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = getJson(url);
            assertThat(page.get("total").asLong()).isEqualTo(total);
            for (JsonNode it : page.get("items")) {
                long id = it.get("id").asLong();
                assertThat(seen.add(id)).isTrue();
            }
            pages++;
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.get("nextCursor").isNull()).isTrue();
                break;
            }
            assertThat(page.get("nextCursor").asText()).isNotBlank();
            cursor = page.get("nextCursor").asText();
            if (pages > 100) break;
        }
        assertThat(seen.size()).isEqualTo((int) total);

        // last page already has hasMore false; request following page using cursor from last item -> empty
        // Build cursor from the smallest (last) item key by re-fetching last page is complex;
        // instead verify an obviously-beyond cursor yields empty page.
        String beyond = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((Instant.EPOCH.toString() + "|0").getBytes(StandardCharsets.UTF_8));
        JsonNode empty = getJson("/api/v1/items?size=" + size + "&cursor=" + beyond);
        assertThat(empty.get("items").size()).isEqualTo(0);
        assertThat(empty.get("hasMore").asBoolean()).isFalse();
        assertThat(empty.get("total").asLong()).isEqualTo(total);
    }

    @Test
    void nullPublished_defaultListingAndDateRangeExclusion() throws Exception {
        // Insert Investing-shaped rows with null published_at
        FeedSource src = sources.findAll().isEmpty() ? null : sources.findAll().get(0);
        if (src == null) {
            src = sources.save(new FeedSource("Seam Source", "http://localhost:" + port + "/feed"));
        }
        Instant t1 = Instant.parse("2025-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2025-02-02T00:00:00Z");
        Item a = new Item(src, "g-null-1", "http://x/1", "Null published one", "summary", null, t1);
        Item b = new Item(src, "g-null-2", "http://x/2", "Null published two", "summary", null, t2);
        items.save(a);
        items.save(b);
        // a dated item
        Item c = new Item(src, "g-dated", "http://x/3", "Dated item", "summary",
                Instant.parse("2025-01-15T00:00:00Z"), t2);
        items.save(c);

        JsonNode def = getJson("/api/v1/items?size=100");
        // null-published rows appear (in ingestion/fetched_at order)
        List<String> titles = new ArrayList<>();
        for (JsonNode it : def.get("items")) titles.add(it.get("title").asText());
        assertThat(titles).contains("Null published one", "Null published two");

        // date range excludes null published_at
        JsonNode ranged = getJson("/api/v1/items?from=2025-01-01T00:00:00Z&to=2025-12-31T23:59:59Z&size=100");
        List<String> rangedTitles = new ArrayList<>();
        for (JsonNode it : ranged.get("items")) rangedTitles.add(it.get("title").asText());
        assertThat(rangedTitles).doesNotContain("Null published one", "Null published two");
        assertThat(rangedTitles).contains("Dated item");
    }

    @Test
    void itemDetail_existingMissingMalformed() throws Exception {
        poller.pollOnce();
        long existingId = getJson("/api/v1/items?size=1").get("items").get(0).get("id").asLong();
        mockMvc.perform(get("/api/v1/items/" + existingId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/items/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));
        mockMvc.perform(get("/api/v1/items/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));
    }

    @Test
    void searchStaleness_guardGeneratedColumn() throws Exception {
        FeedSource src = sources.findAll().isEmpty() ? sources.save(new FeedSource("Seam Source", "http://localhost:" + port + "/feed"))
                : sources.findAll().get(0);
        String token = "stalenessguardtoken123";
        Item item = new Item(src, "g-stale", "http://x/stale", "Title contains " + token, "summary", null,
                Instant.now());
        items.save(item);

        JsonNode res = getJson("/api/v1/items?q=" + token + "&size=100");
        assertThat(res.get("items").size()).isGreaterThan(0);
        boolean found = false;
        for (JsonNode it : res.get("items")) {
            if (it.get("title").asText().contains(token)) found = true;
        }
        assertThat(found).isTrue();
    }

    @Test
    void openApi_docsContainsPaths() throws Exception {
        MvcResult res = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertThat(body).contains("/api/v1/items");
        assertThat(body).contains("/api/v1/items/{id}");
    }
}
