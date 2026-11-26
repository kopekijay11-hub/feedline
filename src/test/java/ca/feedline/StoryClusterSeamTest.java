package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.domain.StoryCluster;
import ca.feedline.domain.StoryClusterMember;
import ca.feedline.dto.ClusterSummaryDto;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.StoryClusterMemberRepository;
import ca.feedline.repository.StoryClusterRepository;
import ca.feedline.service.StoryClusterService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StoryClusterSeamTest {

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
        registry.add("feed.cluster.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StoryClusterService service;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private StoryClusterRepository clusters;

    @Autowired
    private StoryClusterMemberRepository members;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant base = Instant.parse("2026-01-01T12:00:00Z");

    @BeforeEach
    @AfterEach
    void clean() {
        members.deleteAllInBatch();
        clusters.deleteAllInBatch();
        items.deleteAllInBatch();
        sources.deleteAllInBatch();
    }

    private FeedSource src(String name) {
        FeedSource s = new FeedSource(name, "http://example/" + name);
        return sources.save(s);
    }

    private Item it(FeedSource source, String guid, String title, String summary, Instant fetchedAt) {
        Item i = new Item(source, guid, "http://example/" + guid, title, summary, fetchedAt, fetchedAt);
        return items.save(i);
    }

    private void pass() {
        service.scheduledPass();
    }

    @Test
    void sameSourceNearIdenticalDoesNotCluster() throws Exception {
        FeedSource s = src("Same Source");
        Instant t = base;
        it(s, "g-1", "Major market move shakes investors", "(AP) markets up", t);
        it(s, "g-2", "Major market move shakes investors", "(AP) markets up", t.plusSeconds(10));

        pass();

        assertThat(clusters.findAll()).isEmpty();
        assertThat(members.findAll()).isEmpty();
    }

    @Test
    void r1ExactJaccardClusters() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "alpha beta gamma delta", "summary", t);
        it(b, "g-b", "alpha beta gamma epsilon", "summary", t.plusSeconds(1));

        pass();

        assertThat(clusters.findAll()).hasSize(1);
        assertThat(members.findAll()).hasSize(2);
    }

    @Test
    void r1JustBelowDoesNotCluster() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "alpha beta gamma delta", "summary", t);
        it(b, "g-b", "alpha beta gamma epsilon zeta", "summary", t.plusSeconds(1));

        pass();

        assertThat(clusters.findAll()).isEmpty();
    }

    @Test
    void r2SameCreditWeakHeadlineClusters() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "alpha beta", "(AP) story", t);
        it(b, "g-b", "alpha beta gamma delta epsilon", "(AP) story", t.plusSeconds(1));

        pass();

        assertThat(clusters.findAll()).hasSize(1);
        StoryClusterMember m = members.findAll().get(0);
        assertThat(m.getMatchedRule()).isEqualTo("R2");
    }

    @Test
    void r2SameCreditWeakHeadlineBelowThresholdDoesNotCluster() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "alpha beta", "(AP) story", t);
        it(b, "g-b", "alpha beta gamma delta epsilon zeta", "(AP) story", t.plusSeconds(1));

        pass();

        assertThat(clusters.findAll()).isEmpty();
    }

    @Test
    void r2DifferentCreditDoesNotCluster() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "alpha beta", "(AP) story", t);
        it(b, "g-b", "gamma delta", "(Reuters) story", t.plusSeconds(1));

        pass();

        assertThat(clusters.findAll()).isEmpty();
    }

    @Test
    void windowEdge49HoursNo47HoursYes() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "unique breaking headline here", "summary", t);
        it(b, "g-b", "unique breaking headline here", "summary", t.plusSeconds(49L * 3600));

        pass();
        assertThat(clusters.findAll()).isEmpty();

        members.deleteAllInBatch();
        clusters.deleteAllInBatch();
        items.deleteAllInBatch();

        it(a, "g-c", "unique breaking headline here", "summary", t);
        it(b, "g-d", "unique breaking headline here", "summary", t.plusSeconds(47L * 3600));

        pass();
        assertThat(clusters.findAll()).hasSize(1);
        assertThat(members.findAll()).hasSize(2);
    }

    @Test
    void threeOutletsOneStoryRepresentativeEarliest() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        FeedSource c = src("Gamma Outlet");
        Instant t = base;
        Item ia = it(a, "g-a", "unique breaking headline here", "summary", t);
        Item ib = it(b, "g-b", "unique breaking headline here", "summary", t.plusSeconds(1));
        Item ic = it(c, "g-c", "unique breaking headline here", "summary", t.plusSeconds(2));

        pass();

        assertThat(clusters.findAll()).hasSize(1);
        StoryCluster cluster = clusters.findAll().get(0);
        assertThat(cluster.getRepresentativeItemId()).isEqualTo(ia.getId());
        List<StoryClusterMember> ms = members.findByClusterId(cluster.getId());
        assertThat(ms).hasSize(3);
    }

    @Test
    void bridgeMergeIntoLowerClusterId() throws Exception {
        FeedSource s1 = src("One");
        FeedSource s2 = src("Two");
        FeedSource s3 = src("Three");
        FeedSource s4 = src("Four");
        FeedSource s5 = src("Five");
        Instant t = base;
        Item i1 = it(s1, "g-1", "alpha beta", "(AP) story", t);
        it(s2, "g-2", "alpha beta gamma delta epsilon", "(AP) story", t.plusSeconds(1));
        it(s3, "g-3", "gamma delta zeta", "(AP) story", t.plusSeconds(2));
        it(s4, "g-4", "gamma delta kappa lambda mu", "(AP) story", t.plusSeconds(3));
        Item c = it(s5, "g-5", "alpha beta gamma delta", "(AP) story", t.plusSeconds(10));

        pass();

        assertThat(clusters.findAll()).hasSize(1);
        StoryCluster cluster = clusters.findAll().get(0);
        List<StoryClusterMember> ms = members.findByClusterId(cluster.getId());
        assertThat(ms).hasSize(5);
        assertThat(cluster.getRepresentativeItemId()).isEqualTo(i1.getId());
    }

    @Test
    void idempotentSecondPassDoesNotChangeTables() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        it(a, "g-a", "unique breaking headline here", "summary", t);
        it(b, "g-b", "unique breaking headline here", "summary", t.plusSeconds(1));

        pass();
        int clusters1 = clusters.findAll().size();
        int members1 = members.findAll().size();

        pass();
        assertThat(clusters.findAll().size()).isEqualTo(clusters1);
        assertThat(members.findAll().size()).isEqualTo(members1);
    }

    @Test
    void apiListShapeAndOrdering() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        FeedSource c = src("Gamma Outlet");
        FeedSource d = src("Delta Outlet");
        Instant t = Instant.now();
        it(a, "g-a", "alpha story one", "(AP) news", t);
        it(b, "g-b", "alpha story two", "(AP) news", t.plusSeconds(1));
        it(c, "g-c", "beta story three", "(Reuters) news", t.plusSeconds(2));
        it(d, "g-d", "beta story four", "(Reuters) news", t.plusSeconds(3));

        pass();

        JsonNode res = getJson("/api/v1/clusters");
        assertThat(res.isArray()).isTrue();
        assertThat(res.size()).isEqualTo(2);
        assertThat(res.get(0).get("memberCount").asInt()).isEqualTo(2);
        assertThat(res.get(1).get("memberCount").asInt()).isEqualTo(2);
        JsonNode first = res.get(0);
        assertThat(first.get("id")).isNotNull();
        assertThat(first.get("representativeTitle")).isNotNull();
        assertThat(first.get("representativeItemId")).isNotNull();
        assertThat(first.get("memberCount").asInt()).isEqualTo(2);
        assertThat(first.get("sourceNames").isArray()).isTrue();
        assertThat(first.get("firstSeenAt")).isNotNull();
        assertThat(first.get("lastSeenAt")).isNotNull();
    }

    @Test
    void apiDetailMembersOrderedAndUnknown404() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = Instant.now();
        Item ia = it(a, "g-a", "shared breaking headline", "(AP) news", t);
        Item ib = it(b, "g-b", "shared breaking headline", "(AP) news", t.plusSeconds(1));

        pass();

        Long clusterId = clusters.findAll().get(0).getId();
        JsonNode res = getJson("/api/v1/clusters/" + clusterId);
        assertThat(res.get("id").asLong()).isEqualTo(clusterId);
        JsonNode membersNode = res.get("members");
        assertThat(membersNode).hasSize(2);
        assertThat(membersNode.get(0).get("itemId").asLong()).isEqualTo(ia.getId());
        assertThat(membersNode.get(1).get("itemId").asLong()).isEqualTo(ib.getId());
        assertThat(membersNode.get(0).get("sourceName").asText()).isEqualTo("Alpha Outlet");
        assertThat(membersNode.get(0).get("title").asText()).isEqualTo("shared breaking headline");

        mockMvc.perform(get("/api/v1/clusters/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"));
    }

    @Test
    void apiUnknownQueryParam400AndSinceHoursCap() throws Exception {
        mockMvc.perform(get("/api/v1/clusters?bogus=1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/problem+json"));

        JsonNode res = getJson("/api/v1/clusters?sinceHours=1000");
        assertThat(res.isArray()).isTrue();
    }

    @Test
    void itemDtoClusterIdNullBeforeSetAfter() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        Instant t = base;
        Item ia = it(a, "g-a", "unique breaking headline here", "summary", t);
        Item ib = it(b, "g-b", "unique breaking headline here", "summary", t.plusSeconds(1));

        JsonNode before = getJson("/api/v1/items?size=100");
        JsonNode beforeNode = findItem(before, ia.getId());
        assertThat(beforeNode.path("clusterId").isMissingNode() || beforeNode.get("clusterId") == null || beforeNode.get("clusterId").isNull()).isTrue();

        pass();

        JsonNode after = getJson("/api/v1/items?size=100");
        assertThat(findItem(after, ia.getId()).get("clusterId").asLong()).isNotNull();
        assertThat(findItem(after, ib.getId()).get("clusterId").asLong()).isEqualTo(
                clusters.findAll().get(0).getId());
    }

    @Test
    void itemDtoClusterSizeMatchesMemberCount() throws Exception {
        FeedSource a = src("Alpha Outlet");
        FeedSource b = src("Beta Outlet");
        FeedSource c = src("Gamma Outlet");
        Instant t = base;
        Item ia = it(a, "g-a", "unique breaking headline here", "summary", t);
        Item ib = it(b, "g-b", "unique breaking headline here", "summary", t.plusSeconds(1));
        Item ic = it(c, "g-c", "unique breaking headline here", "summary", t.plusSeconds(2));
        Item un = it(a, "g-u", "unrelated story about nothing in particular", "summary", t);

        pass();

        Long clusterId = clusters.findAll().get(0).getId();
        int memberCount = members.findByClusterId(clusterId).size();
        assertThat(memberCount).isEqualTo(3);

        JsonNode after = getJson("/api/v1/items?size=100");
        for (JsonNode node : after.get("items")) {
            Long id = node.get("id").asLong();
            if (List.of(ia.getId(), ib.getId(), ic.getId()).contains(id)) {
                assertThat(node.get("clusterId").asLong()).isEqualTo(clusterId);
                assertThat(node.get("clusterSize").asInt()).isEqualTo(memberCount);
            } else if (id.equals(un.getId())) {
                assertThat(node.path("clusterId").isMissingNode() || node.get("clusterId") == null || node.get("clusterId").isNull()).isTrue();
                assertThat(node.path("clusterSize").isMissingNode() || node.get("clusterSize") == null || node.get("clusterSize").isNull()).isTrue();
            }
        }
    }

    private JsonNode getJson(String path) throws Exception {
        return mapper.readTree(mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    private JsonNode findItem(JsonNode page, Long itemId) {
        for (JsonNode node : page.get("items")) {
            if (node.get("id").asLong() == itemId) {
                return node;
            }
        }
        throw new AssertionError("item " + itemId + " not found");
    }

    private Long s1ItemId(FeedSource s) {
        return items.findBySourceIdOrderByFetchedAtDesc(s.getId()).stream()
                .findFirst().map(Item::getId).orElse(null);
    }


}
