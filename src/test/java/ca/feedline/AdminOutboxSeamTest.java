package ca.feedline;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.OutboxRelayService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
@AutoConfigureMockMvc
class AdminOutboxSeamTest {

    private static final String TOPIC = "feedline.items.ingested";
    private static final String TOKEN = "test-admin-token";

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
        registry.add("feed.outbox.enabled", () -> "true");
        registry.add("feed.outbox.topic", () -> TOPIC);
        registry.add("feed.outbox.poll-delay", () -> "86400000");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.admin.api-key", () -> TOKEN);
        registry.add("feed.outbox.initial-backoff-ms", () -> "100");
        registry.add("feed.outbox.max-backoff-ms", () -> "1000");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private OutboxRelayService relay;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        outboxEvents.deleteAll();
        ensureTopic();
    }

    @AfterEach
    void tearDown() {
        outboxEvents.deleteAll();
    }

    @Test
    void authEnforcement() throws Exception {
        mockMvc.perform(get("/api/v1/admin/outbox/poisoned"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));

        mockMvc.perform(get("/api/v1/admin/outbox/poisoned").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));

        mockMvc.perform(get("/api/v1/items"))
                .andExpect(status().isOk());
    }

    @Test
    void listingAndPagination() throws Exception {
        OutboxEvent poisonedNewer = new OutboxEvent(1L, TOPIC, "poison-newer", "{}");
        poisonedNewer.markPoisoned(Instant.now());
        outboxEvents.saveAndFlush(poisonedNewer);

        OutboxEvent poisonedOlder = new OutboxEvent(2L, TOPIC, "poison-older", "{}");
        poisonedOlder.markPoisoned(Instant.now().minusSeconds(60));
        outboxEvents.saveAndFlush(poisonedOlder);

        OutboxEvent pending = new OutboxEvent(3L, TOPIC, "pending-key", "{}");
        outboxEvents.saveAndFlush(pending);

        OutboxEvent published = new OutboxEvent(4L, TOPIC, "published-key", "{}");
        published.markPublished(Instant.now());
        outboxEvents.saveAndFlush(published);

        MvcResult res = mockMvc.perform(get("/api/v1/admin/outbox/poisoned")
                        .header("X-Admin-Token", TOKEN)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.get("items").size()).isEqualTo(2);
        assertThat(json.get("totalElements").asLong()).isEqualTo(2);
        assertThat(json.get("items").get(0).get("eventKey").asText()).isEqualTo("poison-newer");
        assertThat(json.get("items").get(1).get("eventKey").asText()).isEqualTo("poison-older");

        mockMvc.perform(get("/api/v1/admin/outbox/poisoned")
                        .header("X-Admin-Token", TOKEN)
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));

        mockMvc.perform(get("/api/v1/admin/outbox/poisoned")
                        .header("X-Admin-Token", TOKEN)
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));

        mockMvc.perform(get("/api/v1/admin/outbox/poisoned")
                        .header("X-Admin-Token", TOKEN)
                        .param("foo", "bar"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("problem+json"));
    }

    @Test
    void singleReplayEndToEnd() throws Exception {
        OutboxEvent poisoned = new OutboxEvent(42L, TOPIC, "poison-key", "{\"hello\":true}");
        poisoned.markPoisoned(Instant.now());
        poisoned = outboxEvents.saveAndFlush(poisoned);

        mockMvc.perform(post("/api/v1/admin/outbox/poisoned/999999/replay")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());

        OutboxEvent pending = new OutboxEvent(7L, TOPIC, "pending-key", "{}");
        pending = outboxEvents.saveAndFlush(pending);
        mockMvc.perform(post("/api/v1/admin/outbox/poisoned/" + pending.getId() + "/replay")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isConflict());

        OutboxEvent published = new OutboxEvent(8L, TOPIC, "published-key", "{}");
        published.markPublished(Instant.now());
        published = outboxEvents.saveAndFlush(published);
        mockMvc.perform(post("/api/v1/admin/outbox/poisoned/" + published.getId() + "/replay")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isConflict());

        outboxEvents.deleteById(pending.getId());
        outboxEvents.deleteById(published.getId());

        mockMvc.perform(post("/api/v1/admin/outbox/poisoned/" + poisoned.getId() + "/replay")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk());

        OutboxEvent requeued = outboxEvents.findById(poisoned.getId()).orElseThrow();
        assertThat(requeued.getPoisonedAt()).isNull();
        assertThat(requeued.getReplayedCount()).isEqualTo(1);
        assertThat(requeued.getPublishedAt()).isNull();

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            relay.publishPending();
            List<ConsumerRecord<String, String>> records = collect(consumer, 1);
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo("poison-key");
            assertThat(records.get(0).value()).isEqualTo("{\"hello\":true}");
        }
        assertThat(outboxEvents.findById(poisoned.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void batchReplayEndToEnd() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            OutboxEvent e = new OutboxEvent(100L + i, TOPIC, "batch-" + i, "{}");
            e.markPoisoned(Instant.now().minusSeconds(i));
            ids.add(outboxEvents.saveAndFlush(e).getId());
        }

        MvcResult res = mockMvc.perform(post("/api/v1/admin/outbox/poisoned/replay-batch")
                        .header("X-Admin-Token", TOKEN)
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(json.get("replayedCount").asInt()).isEqualTo(3);
        assertThat(json.get("replayedIds").size()).isEqualTo(3);

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            relay.publishPending();
            List<ConsumerRecord<String, String>> records = collect(consumer, 3);
            assertThat(records).extracting(ConsumerRecord::key)
                    .containsExactlyInAnyOrder("batch-0", "batch-1", "batch-2");
        }
    }

    private void ensureTopic() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                        .all().get(15, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
            }
        }
    }

    private KafkaConsumer<String, String> consumerAtEnd() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties());
        consumer.subscribe(List.of(TOPIC));
        long deadline = System.currentTimeMillis() + 20_000;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(java.time.Duration.ofMillis(200));
        }
        org.assertj.core.api.Assertions.assertThat(consumer.assignment()).isNotEmpty();
        java.util.Map<TopicPartition, Long> endOffsets = consumer.endOffsets(consumer.assignment());
        for (TopicPartition partition : consumer.assignment()) {
            long offset = endOffsets.get(partition);
            consumer.seek(partition, offset);
        }
        return consumer;
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "feedline-admin-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private List<ConsumerRecord<String, String>> collect(KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 30_000;
        while (records.size() < expected && System.currentTimeMillis() < deadline) {
            consumer.poll(java.time.Duration.ofMillis(300)).forEach(records::add);
        }
        return records;
    }
}
