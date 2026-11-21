package ca.feedline;

import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.FeedSource;
import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.FeedPollerService;
import ca.feedline.service.OutboxCleanupService;
import ca.feedline.service.OutboxRelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Import(FeedlineLifecycleE2ETest.ToggleKafkaConfig.class)
class FeedlineLifecycleE2ETest {

    static final java.util.concurrent.atomic.AtomicBoolean FAIL_SENDS = new java.util.concurrent.atomic.AtomicBoolean(false);

    @TestConfiguration
    static class ToggleKafkaConfig {
        @Bean
        @Primary
        KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
            return new KafkaTemplate<>(pf) {
                @Override
                public java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> send(
                        org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {
                    if (FAIL_SENDS.get()) {
                        java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> f = new java.util.concurrent.CompletableFuture<>();
                        f.completeExceptionally(new RuntimeException("injected transient send failure"));
                        return f;
                    }
                    return super.send(record);
                }
            };
        }
    }

    private static final String TOPIC = "feedline.items.ingested";
    private static final String TOKEN = "e2e-admin-token";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.16-alpine")
            .withDatabaseName("feedline")
            .withUsername("feedline")
            .withPassword("feedline");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("feed.poll-delay", () -> "86400000");
        registry.add("feed.outbox.enabled", () -> "true");
        registry.add("feed.outbox.topic", () -> TOPIC);
        registry.add("feed.outbox.poll-delay", () -> "86400000");
        registry.add("feed.outbox.max-attempts", () -> "3");
        registry.add("feed.outbox.initial-backoff-ms", () -> "10");
        registry.add("feed.outbox.max-backoff-ms", () -> "50");
        registry.add("feed.outbox.send-timeout-ms", () -> "5000");
        registry.add("feed.outbox.cleanup.enabled", () -> "true");
        registry.add("feed.outbox.cleanup.batch-size", () -> "10");
        registry.add("feed.outbox.cleanup.published-retention", () -> "1s");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.admin.enabled", () -> "true");
        registry.add("feed.admin.api-key", () -> TOKEN);
        registry.add("feed.ssrf-allow-loopback", () -> "true");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private FeedPollerService poller;
    @Autowired private OutboxRelayService relay;
    @Autowired private OutboxCleanupService cleanup;
    @Autowired private FeedSourceRepository sources;
    @Autowired private ItemRepository items;
    @Autowired private OutboxEventRepository outboxEvents;
    @Autowired private IngestSkipRepository skips;
    @Autowired private ObjectMapper mapper;

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        outboxEvents.deleteAll();
        skips.deleteAll();
        items.deleteAll();
        sources.deleteAll();
        ensureTopic();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/robots.txt", this::serveRobots);
        server.createContext("/feed", this::serveFeed);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        outboxEvents.deleteAll();
        skips.deleteAll();
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void fullLifecycle_pollToReplayToCleanup() throws Exception {
        // 1) Poll & ingest with robots stub.
        int saved = poller.pollSingleSource(source());
        assertThat(saved).isEqualTo(2);
        assertThat(items.count()).isEqualTo(2);

        FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
        assertThat(stored.getLeaseOwner()).isNull();
        assertThat(stored.getLeaseUntil()).isNull();

        List<OutboxEvent> emitted = outboxEvents.findAll();
        assertThat(emitted).hasSize(2).allSatisfy(e -> {
            assertThat(e.getSchemaVersion()).isEqualTo(1);
            assertThat(e.getEventKey()).startsWith(stored.getId() + ":");
            assertThat(e.getPublishedAt()).isNull();
            JsonNode payload = mapper.readTree(e.getPayload());
            assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(payload.get("eventKey").asText()).isEqualTo(e.getEventKey());
            assertThat(payload.get("eventType").asText()).isEqualTo("ItemIngested");
            assertThat(payload.get("occurredAt").asText()).isNotBlank();
            assertThat(payload.get("sourceId").asLong()).isEqualTo(stored.getId());
        });

        // 2) Transient failures across retries until maxAttempts reached -> poison.
        FAIL_SENDS.set(true);
        try {
            for (int i = 0; i < 20 && outboxEvents.findAll().stream().noneMatch(OutboxEvent::isPoisoned); i++) {
                relay.publishPending();
                makePublishableNow();
            }
        } finally {
            FAIL_SENDS.set(false);
        }

        List<OutboxEvent> afterFailures = outboxEvents.findAll();
        OutboxEvent poisoned = afterFailures.stream().filter(OutboxEvent::isPoisoned).findFirst().orElseThrow();
        assertThat(poisoned.getPoisonedAt()).isNotNull();
        assertThat(poisoned.getLastAttemptAt()).as("V12 last_attempt_at").isNotNull();
        assertThat(poisoned.getLastError()).isNotBlank();
        assertThat(poisoned.getLastError())
                .doesNotContain("\n")
                .doesNotContain("Exception in thread")
                .doesNotContain("at ca.feedline");
        // Sanitizer should cap/clean; secret-free by construction here, but format must be safe.
        assertThat(poisoned.getLastError().length()).isLessThanOrEqualTo(500);

        // Admin endpoint auth + poisoned listing.
        mockMvc.perform(get("/api/v1/admin/outbox/poisoned"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/outbox/poisoned").header("X-Admin-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        MvcResult poisonedPage = mockMvc.perform(get("/api/v1/admin/outbox/poisoned")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = mapper.readTree(poisonedPage.getResponse().getContentAsString());
        assertThat(page.get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode row : page.get("items")) {
            if (row.get("id").asLong() == poisoned.getId()) {
                found = true;
                assertThat(row.get("lastAttemptAt").isNull()).isFalse();
                assertThat(row.get("lastError").asText()).isNotBlank();
            }
        }
        assertThat(found).isTrue();

        // 3) Replay poisoned event and verify real Kafka delivery with original metadata.
        String originalKey = poisoned.getEventKey();
        String originalPayload = poisoned.getPayload();
        int originalSchema = poisoned.getSchemaVersion();

        MvcResult replayRes = mockMvc.perform(post("/api/v1/admin/outbox/poisoned/{id}/replay", poisoned.getId())
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode replayJson = mapper.readTree(replayRes.getResponse().getContentAsString());
        assertThat(replayJson.get("replayedCount").asInt()).isEqualTo(1);

        OutboxEvent requeued = outboxEvents.findById(poisoned.getId()).orElseThrow();
        assertThat(requeued.getPoisonedAt()).isNull();
        assertThat(requeued.getPublishedAt()).isNull();
        assertThat(requeued.getReplayedCount()).isEqualTo(1);

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            for (int i = 0; i < 20 && outboxEvents.findById(poisoned.getId()).orElseThrow().getPublishedAt() == null; i++) {
                relay.publishPending();
                makePublishableNow();
                Thread.sleep(200);
            }

            ConsumerRecord<String, String> replayedRecord = awaitRecord(consumer, originalKey, Duration.ofSeconds(30));
            assertThat(replayedRecord.key()).isEqualTo(originalKey);
            assertThat(replayedRecord.value()).isEqualTo(originalPayload);
            assertThat(header(replayedRecord, "schemaVersion")).isEqualTo(String.valueOf(originalSchema));
            assertThat(header(replayedRecord, "eventKey")).isEqualTo(originalKey);
            assertThat(header(replayedRecord, "eventType")).isEqualTo("ItemIngested");
            assertThat(header(replayedRecord, "occurredAt")).isNotBlank();
            assertThat(header(replayedRecord, "sourceId")).isEqualTo(String.valueOf(stored.getId()));
        }

        OutboxEvent publishedPoisoned = outboxEvents.findById(poisoned.getId()).orElseThrow();
        assertThat(publishedPoisoned.getPublishedAt()).isNotNull();
        assertThat(publishedPoisoned.getReplayedCount()).isEqualTo(1);

        // Drain remaining healthy event(s).
        for (int i = 0; i < 20 && outboxEvents.findAll().stream().anyMatch(e -> !e.isPublished() && !e.isPoisoned()); i++) {
            relay.publishPending();
            makePublishableNow();
            Thread.sleep(100);
        }

        // 4) Health, query, and retention cleanup.
        MvcResult healthRes = mockMvc.perform(get("/api/v1/sources/health"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode health = mapper.readTree(healthRes.getResponse().getContentAsString());
        assertThat(health.get("status").asText()).isIn("UP", "DEGRADED");
        JsonNode sourceRows = health.get("sources");
        assertThat(sourceRows.isArray()).isTrue();
        boolean healthySource = false;
        for (JsonNode row : sourceRows) {
            if ("E2E Source".equals(row.get("name").asText())) {
                healthySource = "UP".equals(row.get("state").asText()) || "HEALTHY".equals(row.get("state").asText())
                        || "CURRENT".equals(row.get("contentFreshness").asText());
                assertThat(row.get("state").asText()).isIn("UP", "HEALTHY", "DEGRADED", "UNKNOWN");
            }
        }
        assertThat(healthySource).as("source health row present").isTrue();

        Instant now = Instant.now();
        MvcResult itemsRes = mockMvc.perform(get("/api/v1/items")
                        .param("fetchedFrom", now.minus(Duration.ofHours(1)).toString())
                        .param("fetchedTo", now.plus(Duration.ofHours(1)).toString())
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode itemsJson = mapper.readTree(itemsRes.getResponse().getContentAsString());
        assertThat(itemsJson.get("items").size()).isEqualTo(2);

        MvcResult emptyRange = mockMvc.perform(get("/api/v1/items")
                        .param("fetchedFrom", "2000-01-01T00:00:00Z")
                        .param("fetchedTo", "2000-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(mapper.readTree(emptyRange.getResponse().getContentAsString()).get("items").size()).isZero();

        // Cleanup: age published rows, preserve pending/poisoned.
        OutboxEvent pending = new OutboxEvent(999001L, TOPIC, "cleanup-pending", "{}");
        OutboxEvent poisonedKeep = new OutboxEvent(999002L, TOPIC, "cleanup-poisoned", "{}");
        poisonedKeep.recordFailure("keep", Instant.now().minusSeconds(120));
        poisonedKeep.markPoisoned(Instant.now().minusSeconds(120));
        outboxEvents.save(pending);
        outboxEvents.save(poisonedKeep);

        for (OutboxEvent e : outboxEvents.findAll()) {
            if (e.isPublished()) {
                e.markPublished(Instant.now().minusSeconds(120));
                outboxEvents.save(e);
            }
        }

        int deleted = cleanup.cleanup();
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(outboxEvents.findAll().stream().anyMatch(e -> "cleanup-pending".equals(e.getEventKey()))).isTrue();
        assertThat(outboxEvents.findAll().stream().anyMatch(e -> "cleanup-poisoned".equals(e.getEventKey()))).isTrue();
        assertThat(outboxEvents.findAll().stream().filter(OutboxEvent::isPublished)).isEmpty();
    }

    private void makePublishableNow() {
        Instant now = Instant.now();
        for (OutboxEvent e : outboxEvents.findAll()) {
            if (!e.isPublished() && !e.isPoisoned() && e.getNextAttemptAt() != null && e.getNextAttemptAt().isAfter(now)) {
                e.scheduleRetry(now.minusSeconds(1));
                outboxEvents.save(e);
            }
        }
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, String key, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<ConsumerRecord<String, String>> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(seen::add);
            for (ConsumerRecord<String, String> r : seen) {
                if (key.equals(r.key())) return r;
            }
        }
        throw new AssertionError("Kafka record not received for key " + key + "; seen=" + seen.stream().map(ConsumerRecord::key).toList());
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        assertThat(record.headers().lastHeader(name)).as("header " + name).isNotNull();
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private void ensureTopic() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) throw e;
            }
        }
    }

    private KafkaConsumer<String, String> consumerAtEnd() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties());
        consumer.subscribe(List.of(TOPIC));
        long deadline = System.currentTimeMillis() + 20_000;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(200));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(consumer.assignment());
        for (TopicPartition partition : consumer.assignment()) {
            consumer.seek(partition, endOffsets.get(partition));
        }
        return consumer;
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "feedline-e2e-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private void serveRobots(HttpExchange exchange) throws IOException {
        byte[] body = "User-agent: *\nAllow: /\n".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }

    private void serveFeed(HttpExchange exchange) throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<rss version=\"2.0\"><channel><title>E2E</title><link>http://localhost</link><description>d</description>"
                + "<item><guid>e2e-1</guid><title>One</title><link>https://example.test/1</link>"
                + "<pubDate>Thu, 20 Nov 2026 00:00:00 GMT</pubDate><description>first</description></item>"
                + "<item><guid>e2e-2</guid><title>Two</title><link>https://example.test/2</link>"
                + "<pubDate>Thu, 20 Nov 2026 00:01:00 GMT</pubDate><description>second</description></item>"
                + "</channel></rss>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private Source source() {
        Source s = new Source("E2E Source", feedUrl());
        s.setEnabled(true);
        return s;
    }

    private String feedUrl() {
        return "http://localhost:" + port + "/feed";
    }
}
