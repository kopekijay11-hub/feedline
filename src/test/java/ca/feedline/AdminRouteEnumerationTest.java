package ca.feedline;

import ca.feedline.config.FeedProperties;
import ca.feedline.dto.OutboxStatsDto;
import ca.feedline.dto.PoisonedOutboxPage;
import ca.feedline.dto.PoisonReplayResponse;
import ca.feedline.service.AdminOutboxService;
import ca.feedline.web.AdminOutboxController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOutboxController.class)
@TestPropertySource(properties = "feed.admin.api-key=test-admin-token")
@Import(AdminRouteEnumerationTest.Config.class)
class AdminRouteEnumerationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String BURNED = "feedline-admin-dev" + "-token";

    static final class Route {
        final String method;
        final String path;
        Route(String method, String path) { this.method = method; this.path = path; }
    }

    private static final List<Route> ROUTES = List.of(
            new Route("GET", "/api/v1/admin/outbox/stats"),
            new Route("GET", "/api/v1/admin/outbox/poisoned"),
            new Route("POST", "/api/v1/admin/outbox/poisoned/1/replay"),
            new Route("POST", "/api/v1/admin/outbox/poisoned/replay-batch"),
            new Route("POST", "/api/v1/admin/outbox/cleanup")
    );

    private MockHttpServletRequestBuilder request(Route r, String path) {
        if ("GET".equals(r.method)) return get(path);
        return post(path);
    }

    @Test
    void noTokenRejectedOnEveryRoute() throws Exception {
        for (Route r : ROUTES) {
            mockMvc.perform(request(r, r.path))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void wrongTokenRejectedOnEveryRoute() throws Exception {
        for (Route r : ROUTES) {
            mockMvc.perform(request(r, r.path).header("X-Admin-Token", "wrong"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void burnedTokenRejectedOnEveryRoute() throws Exception {
        for (Route r : ROUTES) {
            mockMvc.perform(request(r, r.path).header("X-Admin-Token", BURNED))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void rightTokenSucceedsOnEveryRoute() throws Exception {
        for (Route r : ROUTES) {
            mockMvc.perform(request(r, r.path).header("X-Admin-Token", "test-admin-token")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void pathVariantsNeverUnauthenticated200() throws Exception {
        String[] variants = new String[]{"", "/", "//", ";x=1", "%20"};
        for (Route r : ROUTES) {
            for (String v : variants) {
                if (v.isEmpty()) continue;
                String path = r.path + v;
                try {
                    int code = mockMvc.perform(request(r, path))
                            .andReturn().getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertNotEquals(200, code,
                            r.method + " " + path + " returned 200 without token");
                } catch (Exception e) {
                    // acceptable (4xx/5xx)
                }
            }
            // double slash in middle
            String mid = r.path.replace("/api/", "/api//");
            try {
                int code = mockMvc.perform(request(r, mid)).andReturn().getResponse().getStatus();
                org.junit.jupiter.api.Assertions.assertNotEquals(200, code, r.method + " " + mid);
            } catch (Exception e) {
            }
        }
    }

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
            when(svc.stats()).thenReturn(new OutboxStatsDto(0, 0, 0));
            when(svc.listPoisoned(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new PoisonedOutboxPage(List.of(), 0, 20, 0, 0));
            when(svc.replayOne(org.mockito.ArgumentMatchers.anyLong()))
                    .thenReturn(new PoisonReplayResponse(0, List.of(), List.of()));
            when(svc.replayBatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(new PoisonReplayResponse(0, List.of(), List.of()));
            when(svc.cleanupPublished()).thenReturn(0);
            return svc;
        }
    }
}
