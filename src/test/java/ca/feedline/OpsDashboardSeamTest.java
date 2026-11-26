package ca.feedline;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OpsDashboardSeamTest {

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
        registry.add("feed.admin.api-key", () -> "test-admin-token");
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rootServesDashboardHtmlWithoutAdminToken() throws Exception {
        String body = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("<title>Feedline Ops Dashboard</title>");
        assertThat(body).contains("id=\"view-reader\"");
        assertThat(body).contains("id=\"view-ops\"");
        assertThat(body).contains("id=\"status-strip\"");
        assertThat(body).contains("id=\"subscribe-popover\"");
        assertThat(body).contains("id=\"view-reader-btn\"");
        assertThat(body).contains("id=\"view-ops-btn\"");
        assertThat(body).contains("System &amp; Outbox Health");
        assertThat(body).contains("Feed Sources");
        assertThat(body).contains("Point-in-Time Items");
        assertThat(body).contains("Outbox Controls");
        assertThat(body).contains("/ops.js");

        mockMvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void staticAssetsArePublic() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        String css = mockMvc.perform(get("/ops.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"))
                .andReturn().getResponse().getContentAsString();
        assertThat(css).contains(".panel");

        String js = mockMvc.perform(get("/ops.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).containsAnyOf("javascript", "text/plain"))
                .andReturn().getResponse().getContentAsString();
        assertThat(js).contains("/api/v1/sources/health");
        assertThat(js).contains("X-Admin-Token");
        assertThat(js).doesNotContain("feedline-admin-dev" + "-token");
    }

    @Test
    void viewSwitchingAssetsPresent() throws Exception {
        String html = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/view.js");

        String viewJs = mockMvc.perform(get("/view.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(viewJs).contains("feedline.view");
    }

    @Test
    void opsJsAddsSortTopWhenQueryPresent() throws Exception {
        String js = mockMvc.perform(get("/ops.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(js).contains("function buildItemsQuery(cursor) {");
        assertThat(js).contains("const q = ui.filterQ.value.trim();");
        assertThat(js).contains("if (q) params.set('q', q);");
        assertThat(js).contains("if (q || state.topStories) params.set('sort', 'top');");
    }

    @Test
    void topStoriesToggleSendsSortTopWithoutQuery() throws Exception {
        String html = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"top-stories\"");
        assertThat(html).contains(">Top stories</button>");

        String js = mockMvc.perform(get("/ops.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(js).contains("topStories: false");
        assertThat(js).contains("topStories: el('top-stories')");
        assertThat(js).contains("state.topStories = !state.topStories;");
        assertThat(js).contains("if (q || state.topStories) params.set('sort', 'top');");
    }

    @Test
    void subscribePopoverAssetsPresent() throws Exception {
        String html = mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/subscribe.js");
        assertThat(html).contains("id=\"subscribe-popover\"");
        assertThat(html).contains("data-feed=\"/feed/trading.xml\"");
        assertThat(html).doesNotContain("business-markets");

        String js = mockMvc.perform(get("/subscribe.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(js).contains("feedly.com/i/subscription/feed/");
        assertThat(js).contains("isPublicOrigin");
        assertThat(js).contains("cannot reach this local address");
    }

    @Test
    void adminEndpointsRemainProtected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/admin/outbox/poisoned"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        mockMvc.perform(get("/api/v1/admin/outbox/stats").header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }
    @Test
    void categoryChipsAndExplorerWindowUpdated() throws Exception {
        String js = mockMvc.perform(get("/ops.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(js).contains("const CATEGORY_ORDER = ['All', 'World', 'Trading'");
        assertThat(js).doesNotContain("Business & Markets");
        assertThat(js).contains("now.getTime() - 7 * 24 * 60 * 60 * 1000");
    }
}
