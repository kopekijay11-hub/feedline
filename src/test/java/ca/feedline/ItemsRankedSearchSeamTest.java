package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.domain.StoryCluster;
import ca.feedline.domain.StoryClusterMember;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.StoryClusterMemberRepository;
import ca.feedline.repository.StoryClusterRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ItemsRankedSearchSeamTest {

    private static final String TERM = "Zephyr";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

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
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.outbox.enabled", () -> "false");
        registry.add("feed.items.search-language", () -> "english");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private StoryClusterRepository clusters;

    @Autowired
    private StoryClusterMemberRepository members;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void clean() {
        members.deleteAll();
        items.deleteAll();
        clusters.deleteAll();
        sources.deleteAll();
    }

    @Test
    void rankedOrder_groupsByEffectiveClusterSizeThenFetchedAtThenId() throws Exception {
        Corpus corpus = seedCorpus();

        JsonNode page = getJson("/api/v1/items?q=" + TERM + "&sort=top&size=100");

        assertThat(page.get("total").asLong()).isEqualTo(corpus.items().size());
        assertThat(ids(page)).containsExactlyElementsOf(expectedRanked(corpus));

        List<Integer> effectiveSizes = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            JsonNode clusterSize = item.get("clusterSize");
            effectiveSizes.add(clusterSize.isNull() ? 1 : clusterSize.asInt());
        }
        assertThat(effectiveSizes).containsExactly(4, 4, 4, 4, 2, 2, 1, 1, 1);
        for (int i = 1; i < effectiveSizes.size(); i++) {
            assertThat(effectiveSizes.get(i - 1)).isGreaterThanOrEqualTo(effectiveSizes.get(i));
        }
    }

    @Test
    void rankedPagination_walksNextCursorWithoutDuplicatesOrSkips() throws Exception {
        Corpus corpus = seedCorpus();
        List<Long> expected = expectedRanked(corpus);
        List<Long> collected = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;

        while (true) {
            String path = "/api/v1/items?q=" + TERM + "&sort=top&size=2"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = getJson(path);
            assertThat(page.get("total").asLong()).isEqualTo(corpus.items().size());
            for (JsonNode item : page.get("items")) {
                long id = item.get("id").asLong();
                assertThat(seen.add(id)).isTrue();
                collected.add(id);
            }
            pages++;
            if (!page.get("hasMore").asBoolean()) {
                assertThat(page.get("nextCursor").isNull()).isTrue();
                break;
            }
            assertThat(page.get("nextCursor").asText()).isNotBlank();
            cursor = page.get("nextCursor").asText();
            assertThat(pages).isLessThan(20);
        }

        assertThat(collected).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo((expected.size() + 1) / 2);
    }

    @Test
    void defaultAndRecent_modesHaveIdenticalRecentOrder() throws Exception {
        Corpus corpus = seedCorpus();

        String defaultBody = getBody("/api/v1/items?q=" + TERM + "&size=100");
        String recentBody = getBody("/api/v1/items?q=" + TERM + "&sort=recent&size=100");

        assertThat(recentBody).isEqualTo(defaultBody);
        assertThat(ids(mapper.readTree(defaultBody))).containsExactlyElementsOf(expectedRecent(corpus));
    }

    @Test
    void topStoriesMode_withoutQueryRanksWholeListing() throws Exception {
        Corpus corpus = seedCorpus();

        JsonNode ranked = getJson("/api/v1/items?sort=top&size=100");
        JsonNode rankedWithQuery = getJson("/api/v1/items?q=" + TERM + "&sort=top&size=100");

        assertThat(ranked.get("total").asLong()).isEqualTo(corpus.items().size());
        assertThat(ids(ranked)).containsExactlyElementsOf(expectedRanked(corpus));
        assertThat(ids(ranked)).containsExactlyElementsOf(ids(rankedWithQuery));
    }

    @Test
    void invalidSortAndCursorModeMismatches_return400() throws Exception {
        Corpus corpus = seedCorpus();
        SeededItem sample = corpus.items().get(0);

        MvcResult bogusSort = mockMvc.perform(get("/api/v1/items?sort=bogus"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(bogusSort.getResponse().getContentType()).contains("problem+json");
        assertThat(bogusSort.getResponse().getContentAsString()).contains("invalid sort value: bogus");

        String recentCursor = encode(sample.fetchedAt().toString() + "|" + sample.id());
        MvcResult recentCursorOnTop = mockMvc.perform(get("/api/v1/items?sort=top&cursor=" + recentCursor))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(recentCursorOnTop.getResponse().getContentType()).contains("problem+json");
        assertThat(recentCursorOnTop.getResponse().getContentAsString()).contains("malformed cursor");

        String rankedCursor = encode(sample.effectiveSize() + "|" + sample.fetchedAt() + "|" + sample.id());
        MvcResult rankedCursorOnRecent = mockMvc.perform(get("/api/v1/items?cursor=" + rankedCursor))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertThat(rankedCursorOnRecent.getResponse().getContentType()).contains("problem+json");
        assertThat(rankedCursorOnRecent.getResponse().getContentAsString()).contains("malformed cursor");
    }

    @Test
    void rankedSourcesFilter_ranksWithinOneSourceOnly() throws Exception {
        Corpus corpus = seedCorpus();
        long sourceId = corpus.sources().get("a").getId();
        List<Long> expected = expectedRanked(corpus).stream()
                .filter(id -> sourceIdOf(corpus, id) == sourceId)
                .toList();

        JsonNode page = getJson("/api/v1/items?q=" + TERM + "&sort=top&sources=" + sourceId + "&size=100");

        assertThat(page.get("total").asLong()).isEqualTo(expected.size());
        assertThat(ids(page)).containsExactlyElementsOf(expected);
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("sourceId").asLong()).isEqualTo(sourceId);
        }
    }

    private Corpus seedCorpus() {
        Map<String, FeedSource> byKey = new LinkedHashMap<>();
        byKey.put("a", sources.save(new FeedSource("Rank A", "https://rank.example.test/a.xml")));
        byKey.put("b", sources.save(new FeedSource("Rank B", "https://rank.example.test/b.xml")));
        byKey.put("c", sources.save(new FeedSource("Rank C", "https://rank.example.test/c.xml")));

        List<SeededItem> all = new ArrayList<>();
        all.add(saveItem(byKey.get("a"), "big-1", 100, 4));
        all.add(saveItem(byKey.get("a"), "big-2", 200, 4));
        all.add(saveItem(byKey.get("b"), "big-3", 300, 4));
        all.add(saveItem(byKey.get("c"), "big-4", 400, 4));
        all.add(saveItem(byKey.get("a"), "small-1", 500, 2));
        all.add(saveItem(byKey.get("b"), "small-2", 600, 2));
        all.add(saveItem(byKey.get("b"), "loose-1", 700, null));
        all.add(saveItem(byKey.get("c"), "loose-2", 700, null));
        all.add(saveItem(byKey.get("c"), "loose-3", 800, null));

        createCluster(List.of(all.get(0), all.get(1), all.get(2), all.get(3)));
        createCluster(List.of(all.get(4), all.get(5)));
        return new Corpus(byKey, List.copyOf(all));
    }

    private SeededItem saveItem(FeedSource source, String key, long offsetSeconds, Integer effectiveSize) {
        Item saved = items.save(new Item(source, "g-" + key, "https://rank.example.test/" + key,
                TERM + " " + key, "summary " + key, null, BASE.plusSeconds(offsetSeconds)));
        return new SeededItem(saved.getId(), source.getId(), BASE.plusSeconds(offsetSeconds), effectiveSize);
    }

    private void createCluster(List<SeededItem> seeded) {
        StoryCluster cluster = new StoryCluster();
        cluster.setRepresentativeItemId(seeded.get(0).id());
        cluster.setCreatedAt(BASE);
        cluster.setFirstSeenAt(BASE);
        cluster.setLastSeenAt(BASE);
        Long clusterId = clusters.save(cluster).getId();
        for (SeededItem item : seeded) {
            StoryClusterMember member = new StoryClusterMember();
            member.setItemId(item.id());
            member.setClusterId(clusterId);
            member.setMatchedRule("TEST");
            member.setSimilarity(1.0d);
            member.setJoinedAt(BASE);
            members.save(member);
        }
    }

    private String getBody(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode getJson(String path) throws Exception {
        return mapper.readTree(getBody(path));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> result = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            result.add(item.get("id").asLong());
        }
        return result;
    }

    private static List<Long> expectedRanked(Corpus corpus) {
        return corpus.items().stream().sorted(RANKED_ORDER).map(SeededItem::id).toList();
    }

    private static List<Long> expectedRecent(Corpus corpus) {
        return corpus.items().stream().sorted(RECENT_ORDER).map(SeededItem::id).toList();
    }

    private static long sourceIdOf(Corpus corpus, long itemId) {
        return corpus.items().stream()
                .filter(item -> item.id() == itemId)
                .findFirst()
                .orElseThrow()
                .sourceId();
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static final Comparator<SeededItem> RANKED_ORDER = Comparator
            .comparingInt((SeededItem item) -> item.effectiveSize() == null ? 1 : item.effectiveSize())
            .reversed()
            .thenComparing(SeededItem::fetchedAt, Comparator.reverseOrder())
            .thenComparing(Comparator.comparingLong(SeededItem::id).reversed());

    private static final Comparator<SeededItem> RECENT_ORDER = Comparator
            .comparing(SeededItem::fetchedAt)
            .reversed()
            .thenComparing(Comparator.comparingLong(SeededItem::id).reversed());

    private record SeededItem(long id, long sourceId, Instant fetchedAt, Integer effectiveSize) {
    }

    private record Corpus(Map<String, FeedSource> sources, List<SeededItem> items) {
    }
}
