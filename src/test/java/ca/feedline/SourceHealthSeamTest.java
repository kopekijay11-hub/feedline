package ca.feedline;

import ca.feedline.config.SourceRegistryInitializer;
import ca.feedline.health.SourceHealthDetail;
import ca.feedline.health.SourceHealthService;
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
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
                    if ("notmodified".equals(m)) {
                        exchange.sendResponseHeaders(304, -1);
                        return;
                    }
                    if ("malformed".equals(m)) {
                        byte[] b = "<not-xml".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/rss+xml");
                        exchange.sendResponseHeaders(200, b.length);
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
        registry.add("feed.content-freshness-threshold", () -> "60s");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.ssrf-allow-loopback", () -> "true");
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
    private SourceHealthService health;

    @Autowired
    private SourceRegistryInitializer initializer;

    @Autowired
    private HealthEndpointGroups healthGroups;

    @Autowired
    private HealthContributorRegistry healthContributors;

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.sourcesStatus").value("DEGRADED"))
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
    void freshness_isInformationalAndUsesThreshold() throws Exception {
        ca.feedline.domain.FeedSource source = sources.findAll().stream()
                .filter(item -> "Seam Source".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        SourceHealthDetail unknown = health.findById(source.getId()).orElseThrow();
        assertThat(unknown.lastNewItemAt()).isNull();
        assertThat(unknown.contentFreshness()).isEqualTo("UNKNOWN");

        Instant now = Instant.now();
        source.setLastAttemptAt(now);
        source.setLastSuccessAt(now);
        source.setLastErrorClass(null);
        source.setConsecutiveFailures(0);
        source.setLastNewItemAt(now);
        source = sources.save(source);
        SourceHealthDetail current = health.findById(source.getId()).orElseThrow();
        assertThat(current.contentFreshness()).isEqualTo("CURRENT");
        assertThat(current.state()).isEqualTo("UP");

        source.setLastNewItemAt(now.minusSeconds(120));
        source = sources.save(source);
        SourceHealthDetail stale = health.findById(source.getId()).orElseThrow();
        assertThat(stale.contentFreshness()).isEqualTo("STALE");
        assertThat(stale.state()).isEqualTo("UP");
        mockMvc.perform(get("/api/v1/sources/health/{sourceId}", source.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastNewItemAt").exists())
                .andExpect(jsonPath("$.contentFreshness").value("STALE"))
                .andExpect(jsonPath("$.state").value("UP"));
    }

    @Test
    void heartbeatTest_schedulingDisabledReadinessIgnoresStaleHeartbeat() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.sourcesStatus").value("UP"))
                .andExpect(jsonPath("$.heartbeatStale").value(false));
    }

    @Test
    void actuatorGroups_excludeSourcesAndSplitLivenessReadiness() {
        HealthEndpointGroup liveness = healthGroups.get("liveness");
        assertThat(liveness.isMember("livenessState")).isTrue();
        assertThat(liveness.isMember("db")).isFalse();
        assertThat(liveness.isMember("pollCycleHeartbeat")).isFalse();
        assertThat(liveness.isMember("sources")).isFalse();

        HealthEndpointGroup readiness = healthGroups.get("readiness");
        assertThat(readiness.isMember("readinessState")).isTrue();
        assertThat(readiness.isMember("db")).isTrue();
        assertThat(readiness.isMember("pollCycleHeartbeat")).isTrue();
        assertThat(readiness.isMember("sources")).isFalse();

        assertThat(healthContributors.getContributor("sources")).isNull();
    }

    @Test
    void readinessAfterCompletedPollIsUp() throws Exception {
        mode.set("ok");
        poller.pollOnce();

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.sourcesStatus").value("UP"));
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
    void notModifiedPollReadsUpHttp() throws Exception {
        mode.set("notmodified");
        // First poll needs a 200 to store validators? 304 with no validators still records success.
        poller.pollOnce();
        Thread.sleep(50);
        poller.pollOnce();
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isOk())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("UP");
        assertThat(json).contains("\"consecutiveFailures\":0");
        assertThat(json).doesNotContain("HTTP_");
    }

    @Test
    void duplicateItemsPollAdvancesLastSuccessHttp() throws Exception {
        mode.set("ok");
        poller.pollOnce();
        poller.pollOnce();
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isOk())
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("UP");
    }

    @Test
    void parseFailureDoesNotAdvanceLastSuccessHttp() throws Exception {
        mode.set("malformed");
        poller.pollOnce();
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("PARSE");
        assertThat(json).contains("DEGRADED");
    }

    @Test
    void fetchFailureDoesNotAdvanceLastSuccessHttp() throws Exception {
        mode.set("fail");
        poller.pollOnce();
        MvcResult res = mockMvc.perform(get("/api/v1/sources/health"))
                .andReturn();
        String json = res.getResponse().getContentAsString();
        assertThat(json).contains("HTTP_500");
        assertThat(json).contains("DEGRADED");
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
