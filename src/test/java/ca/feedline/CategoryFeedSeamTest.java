package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class CategoryFeedSeamTest {

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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @BeforeEach
    void setUp() {
        items.deleteAll();
        sources.deleteAll();
        FeedSource world = sources.save(new FeedSource("BBC News World", "https://example.test/world"));
        FeedSource tech = sources.save(new FeedSource("The Verge", "https://example.test/tech"));
        Instant base = Instant.parse("2026-11-25T12:00:00Z");
        for (int i = 0; i < 55; i++) {
            items.save(new Item(world, "world-" + i, "https://example.test/world/" + i,
                    "World item " + i, "summary " + i, base.plusSeconds(i), base.plusSeconds(i)));
        }
        items.save(new Item(tech, "tech-null", "https://example.test/tech/null",
                "Tech null published", "tech summary", null, base.plusSeconds(100)));
        items.save(new Item(tech, "tech-hostile", "https://example.test/tech/hostile",
                "<script>alert(\"x\")</script> & hostile", "<![CDATA[summary <b>bold</b> & more]]>",
                base.plusSeconds(101), base.plusSeconds(101)));
    }

    @Test
    void categoryFeedContainsOnlyCategorySourcesAndCapsAtFifty() throws Exception {
        MvcResult result = mockMvc.perform(get("/feed/world.xml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).contains("application/rss+xml");
        assertThat(body).contains("<title>Feedline \u2014 World</title>");
        assertThat(body).contains("World item 54");
        assertThat(body).doesNotContain("Tech null published");

        SyndFeed feed = new SyndFeedInput().build(new StringReader(body));
        assertThat(feed.getEntries()).hasSize(50);
        assertThat(feed.getEntries().get(0).getTitle()).isEqualTo("World item 54");
    }

    @Test
    void allFeedSpansCategoriesAndUsesFetchedAtFallback() throws Exception {
        String body = mockMvc.perform(get("/feed/all.xml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        SyndFeed feed = new SyndFeedInput().build(new StringReader(body));
        assertThat(feed.getEntries()).hasSize(50);
        assertThat(feed.getEntries().stream().map(SyndEntry::getTitle).toList())
                .contains("Tech null published");
    }

    @Test
    void unknownCategoryIsNotFound() throws Exception {
        mockMvc.perform(get("/feed/not-a-category.xml")).andExpect(status().isNotFound());
    }

    @Test
    void businessMarketsRedirectsToTrading() throws Exception {
        mockMvc.perform(get("/feed/business-markets.xml"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/feed/trading.xml"));
    }

    @Test
    void conditionalGetReturnsNotModified() throws Exception {
        MvcResult first = mockMvc.perform(get("/feed/world.xml"))
                .andExpect(status().isOk())
                .andReturn();
        String etag = first.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        mockMvc.perform(get("/feed/world.xml").header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    void hostileTextRoundTripsAsInertText() throws Exception {
        String body = mockMvc.perform(get("/feed/technology.xml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        SyndFeed feed = new SyndFeedInput().build(new StringReader(body));
        SyndEntry hostile = feed.getEntries().stream()
                .filter(e -> e.getTitle().contains("hostile"))
                .findFirst()
                .orElseThrow();
        assertThat(hostile.getTitle()).isEqualTo("<script>alert(\"x\")</script> & hostile");
        assertThat(hostile.getDescription().getValue()).isEqualTo("<![CDATA[summary <b>bold</b> & more]]>");
    }

    @Test
    void opmlListsEveryCategoryAndAllFeed() throws Exception {
        String body = mockMvc.perform(get("/feed/index.opml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> expected = List.of(
                "/feed/world.xml", "/feed/trading.xml", "/feed/canada.xml",
                "/feed/united-states.xml", "/feed/europe.xml", "/feed/asia-pacific.xml",
                "/feed/technology.xml", "/feed/official-releases.xml", "/feed/company-news.xml",
                "/feed/all.xml");
        for (String path : expected) {
            assertThat(body).contains(path);
        }
    }
}
