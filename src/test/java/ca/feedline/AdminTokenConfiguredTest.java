package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.service.AdminOutboxService;
import ca.feedline.web.AdminOutboxController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOutboxController.class)
@TestPropertySource(properties = "feed.admin.api-key=test-admin-token")
@Import(AdminTokenConfiguredTest.Config.class)
class AdminTokenConfiguredTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class Config {
        @Bean
        FeedProperties feedProperties() {
            FeedProperties p = new FeedProperties();
            p.getAdmin().setApiKey("test-admin-token");
            return p;
        }

        @Bean
        AdminOutboxService adminOutboxService() {
            AdminOutboxService svc = mock(AdminOutboxService.class);
            when(svc.stats()).thenReturn(null);
            return svc;
        }
    }

    @Test
    void wrongTokenRejectedAndRightTokenSucceedsOnStats() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox/stats").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/outbox/stats").header("X-Admin-Token", "test-admin-token"))
                .andExpect(status().isOk());
    }
}
