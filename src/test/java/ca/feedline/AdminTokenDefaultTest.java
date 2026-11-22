package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.web.AdminOutboxController;
import ca.feedline.service.AdminOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOutboxController.class)
@TestPropertySource(properties = "feed.admin.api-key=")
@Import(AdminTokenDefaultTest.Config.class)
class AdminTokenDefaultTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Config {
        @Bean
        FeedProperties feedProperties() {
            FeedProperties p = new FeedProperties();
            // leave api key null (default)
            return p;
        }

        @Bean
        AdminOutboxService adminOutboxService() {
            return mock(AdminOutboxService.class);
        }
    }

    @Test
    void noKeyConfiguredRejectsNoTokenAndOldString() throws Exception {
        String oldToken = "feedline-admin-dev" + "-token";
        mockMvc.perform(get("/api/v1/admin/outbox/stats"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/outbox/stats").header("X-Admin-Token", oldToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/outbox/poisoned"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/outbox/poisoned").header("X-Admin-Token", oldToken))
                .andExpect(status().isUnauthorized());
    }
}
