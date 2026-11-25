package ca.feedline;

import ca.feedline.config.SourceRegistryInitializer;
import ca.feedline.domain.FeedSource;
import ca.feedline.health.ScorecardDeductionRule;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SourceScorecardSeamTest {

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
        registry.add("feed.poll-delay", () -> "60000");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.sources[0].name", () -> "A Up");
        registry.add("feed.sources[0].url", () -> "http://example.test/a");
        registry.add("feed.sources[0].enabled", () -> "true");
        registry.add("feed.sources[1].name", () -> "B Degraded");
        registry.add("feed.sources[1].url", () -> "http://example.test/b");
        registry.add("feed.sources[1].enabled", () -> "true");
        registry.add("feed.sources[2].name", () -> "C Disabled");
        registry.add("feed.sources[2].url", () -> "http://example.test/c");
        registry.add("feed.sources[2].enabled", () -> "false");
        registry.add("feed.sources[3].name", () -> "D Never");
        registry.add("feed.sources[3].url", () -> "http://example.test/d");
        registry.add("feed.sources[3].enabled", () -> "true");
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

    @Test
    void scorecardIsBareArraySortedByIdAndHasExpectedShape() throws Exception {
        Instant now = Instant.now();
        FeedSource up = byName("A Up");
        up.setLastAttemptAt(now.minusSeconds(30));
        up.setLastSuccessAt(now.minusSeconds(30));
        up.setLastErrorClass(null);
        up.setConsecutiveFailures(0);
        up.setLastNewItemAt(now.minusSeconds(30));
        up.setEtag("etag");
        sources.save(up);

        FeedSource degraded = byName("B Degraded");
        degraded.setLastAttemptAt(now.minusSeconds(30));
        degraded.setLastSuccessAt(now.minusSeconds(30));
        degraded.setLastErrorClass("HTTP_500");
        degraded.setConsecutiveFailures(3);
        degraded.setLastNewItemAt(now.minusSeconds(30));
        degraded.setEtag("etag");
        sources.save(degraded);

        MvcResult res = mockMvc.perform(get("/api/v1/sources/scorecard"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(root.isArray()).isTrue();
        assertThat(root.size()).isEqualTo(4);

        long prev = Long.MIN_VALUE;
        for (JsonNode node : root) {
            long id = node.get("id").asLong();
            assertThat(id).isGreaterThan(prev);
            prev = id;
            assertThat(node.has("name")).isTrue();
            assertThat(node.has("state")).isTrue();
            assertThat(node.has("grade")).isTrue();
            assertThat(node.has("score")).isTrue();
            assertThat(node.has("deductions")).isTrue();
            assertThat(node.get("deductions").isArray()).isTrue();
        }

        JsonNode degradedNode = findByName(root, "B Degraded");
        assertThat(degradedNode.get("grade").asText()).isEqualTo("D");
        assertThat(degradedNode.get("score").asInt()).isEqualTo(45);

        JsonNode disabledNode = findByName(root, "C Disabled");
        assertThat(disabledNode.get("state").asText()).isEqualTo("DISABLED");
        assertThat(disabledNode.get("grade").isNull()).isTrue();
        assertThat(disabledNode.get("score").isNull()).isTrue();
        assertThat(disabledNode.get("deductions")).isEmpty();

        JsonNode neverNode = findByName(root, "D Never");
        assertThat(neverNode.get("state").asText()).isEqualTo("UNKNOWN");
        assertThat(neverNode.get("grade").isNull()).isTrue();
        assertThat(neverNode.get("score").isNull()).isTrue();
        assertThat(neverNode.get("deductions")).isEmpty();
    }

    @Test
    void unknownQueryParamReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/sources/scorecard?bogus=1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void deductionRuleNamesAreClosedEnum() throws Exception {
        Instant now = Instant.now();
        FeedSource degraded = byName("B Degraded");
        degraded.setLastAttemptAt(now.minusSeconds(30));
        degraded.setLastSuccessAt(now.minusSeconds(30));
        degraded.setLastErrorClass("HTTP_500");
        degraded.setConsecutiveFailures(3);
        degraded.setLastNewItemAt(now.minusSeconds(30));
        degraded.setEtag("etag");
        sources.save(degraded);

        JsonNode root = mapper.readTree(mockMvc.perform(get("/api/v1/sources/scorecard"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Set<String> allowed = EnumSet.allOf(ScorecardDeductionRule.class).stream().map(Enum::name).collect(Collectors.toSet());
        for (JsonNode node : root) {
            for (JsonNode d : node.get("deductions")) {
                assertThat(allowed).contains(d.get("rule").asText());
                assertThat(d.get("points").asInt()).isPositive();
            }
        }
    }

    private FeedSource byName(String name) {
        return sources.findAll().stream().filter(s -> name.equals(s.getName())).findFirst().orElseThrow();
    }

    private static JsonNode findByName(JsonNode root, String name) {
        return StreamSupport.stream(root.spliterator(), false)
                .filter(n -> name.equals(n.get("name").asText()))
                .findFirst()
                .orElseThrow();
    }
}
