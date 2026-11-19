package ca.feedline;

import ca.feedline.config.SourceRegistryInitializer;
import ca.feedline.health.SourceHealthDetail;
import ca.feedline.health.SourcesHealth;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.service.FeedPollerService;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class SourceHealthSeamTest {

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
                    String m = mode.get();
                    if ("fail".equals(m)) {
                        byte[] b = "boom".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500, b.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(b); }
                        return;
                    }
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

    private static String readResourceStatic(String path) {
        try (InputStream is = SourceHealthSeamTest.class.getResourceAsStream(path)) {
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
        mode.set("ok");
    }

    @AfterEach
    void tearDown() {
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void degradeTest_failedPollSourceDegradedHttp() throws Exception {
        mode.set("fail");
        poller.pollOnce();
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isServiceUnavailable())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("DEGRADED");
        assertThat(json).contains("HTTP_500");
    }

    @Test
    void neverPolledTest_unknownHttp() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("UNKNOWN");
    }

    @Test
    void heartbeatTest_schedulingDisabledAggregate503() throws Exception {
        // poll-delay huge => no automatic cycle; ensure none completed
        mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownSourceTest_404ProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/sources/health/999999"))
                .andExpect(status().isNotFound())
                .andExpect(result -> {
                    String ct = result.getResponse().getContentType();
                    assertThat(ct).contains("problem+json");
                });
    }

    @Test
    void canary_ingestedSourceUpHttp() throws Exception {
        mode.set("ok");
        poller.pollOnce();
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isOk())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("UP");
    }
}
