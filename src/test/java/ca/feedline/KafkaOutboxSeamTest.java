package ca.feedline;

import ca.feedline.config.FeedProperties.Source;
import ca.feedline.domain.Item;
import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.IngestSkipRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.FeedPollerService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
@SpringBootTest
class KafkaOutboxSeamTest {

    private static final String TOPIC = "feedline.items.ingested";
    private static final String ETAG = "\"outbox-etag\"";
    private static final String LAST_MODIFIED = "Wed, 19 Nov 2025 00:00:00 GMT";
    private static final String PUB_DATE = "Thu, 12 Nov 2026 21:00:42 GMT";

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
        registry.add("feed.outbox.poll-delay", () -> "86400000");
        registry.add("feed.outbox.topic", () -> TOPIC);
        registry.add("feed.outbox.batch-size", () -> "50");
        registry.add("feed.ssrf-allow-loopback", () -> "true");
        registry.add("feed.outbox.initial-backoff-ms", () -> "100");
        registry.add("feed.outbox.max-backoff-ms", () -> "1000");
        registry.add("feed.scheduling-enabled", () -> "false");
    }

    @Autowired
    private FeedPollerService poller;

    @Autowired
    private OutboxRelayService relay;

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private IngestSkipRepository skips;

    @Autowired
    private ObjectMapper mapper;

    private HttpServer server;
    private int port;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>();
    private final AtomicReference<String> lastModified = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        outboxEvents.deleteAll();
        skips.deleteAll();
        items.deleteAll();
        sources.deleteAll();
        body.set("");
        etag.set(null);
        lastModified.set(null);
        ensureTopic();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/feed", this::serveFeed);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        outboxEvents.deleteAll();
        skips.deleteAll();
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void endToEnd_threeNewItemsAndTwoDuplicates_publishExactlyThreeMessages() throws Exception {
        etag.set(ETAG);
        lastModified.set(LAST_MODIFIED);
        body.set(feed(
                entry("seam-1", "First", "https://example.test/1"),
                entry("seam-2", "Second", "https://example.test/2"),
                entry("seam-3", "Third", "https://example.test/3"),
                entry("seam-1", "First duplicate", "https://example.test/1"),
                entry("seam-3", "Third duplicate", "https://example.test/3")));

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            assertThat(poller.pollSingleSource(source())).isEqualTo(3);
            assertThat(items.count()).isEqualTo(3);
            assertThat(outboxEvents.findAll()).hasSize(3)
                    .allSatisfy(event -> assertThat(event.getPublishedAt()).isNull());

            relay.publishPending();

            assertThat(outboxEvents.findAll()).hasSize(3)
                    .allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());
            ca.feedline.domain.FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
            assertThat(stored.getEtag()).isEqualTo(ETAG);
            assertThat(stored.getLastModified()).isEqualTo(LAST_MODIFIED);

            List<ConsumerRecord<String, String>> records = collect(consumer, 3);
            assertThat(records).hasSize(3);
            assertThat(records).extracting(ConsumerRecord::key)
                    .containsExactlyInAnyOrderElementsOf(Set.of(
                            stored.getId() + ":seam-1",
                            stored.getId() + ":seam-2",
                            stored.getId() + ":seam-3"));
            for (ConsumerRecord<String, String> record : records) {
                assertPayload(record, stored.getId());
            }

            relay.publishPending();
            assertThat(consumer.poll(Duration.ofSeconds(2)).count()).isZero();
        }
    }

    @Test
    void kafkaOutage_itemsValidatorsAndOutboxCommitThenRelayAfterRecovery() throws Exception {
        etag.set(ETAG);
        lastModified.set(LAST_MODIFIED);
        body.set(feed(
                entry("outage-1", "Outage one", "https://example.test/outage-1"),
                entry("outage-2", "Outage two", "https://example.test/outage-2")));

        String containerId = kafka.getContainerId();
        assertThat(containerId).as("Kafka Testcontainer id").isNotNull();
        DockerClientFactory.instance().client().pauseContainerCmd(containerId).exec();
        try {
            assertThat(poller.pollSingleSource(source())).isEqualTo(2);
            assertThat(items.count()).isEqualTo(2);
            ca.feedline.domain.FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
            assertThat(stored.getEtag()).isEqualTo(ETAG);
            assertThat(stored.getLastModified()).isEqualTo(LAST_MODIFIED);
            assertThat(outboxEvents.findAll()).hasSize(2)
                    .allSatisfy(event -> assertThat(event.getPublishedAt()).isNull());

            Instant beforeFailure = Instant.now();
            assertDoesNotThrow(relay::publishPending);
            assertThat(outboxEvents.findAll()).hasSize(2)
                    .allSatisfy(event -> assertThat(event.getPublishedAt()).isNull());
            List<OutboxEvent> retried = outboxEvents.findAll().stream()
                    .filter(event -> event.getAttempts() > 0)
                    .toList();
            assertThat(retried).hasSize(1)
                    .singleElement()
                    .satisfies(event -> {
                        assertThat(event.getAttempts()).isEqualTo(1);
                        assertThat(event.getNextAttemptAt()).isAfter(beforeFailure);
                        assertThat(event.getPoisonedAt()).isNull();
                        assertThat(event.getLastError()).isNotBlank();
                    });
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(containerId).exec();
        }

        ca.feedline.domain.FeedSource stored = sources.findByUrl(feedUrl()).orElseThrow();
        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            long deadline = System.currentTimeMillis() + 45_000;
            while (publishedCount() < 2 && System.currentTimeMillis() < deadline) {
                relay.publishPending();
                Thread.sleep(400);
            }
            assertThat(publishedCount()).isEqualTo(2);

            List<ConsumerRecord<String, String>> records = collect(consumer, 2);
            assertThat(records).extracting(ConsumerRecord::key)
                    .contains(stored.getId() + ":outage-1", stored.getId() + ":outage-2");
            Set<String> seenGuids = new java.util.HashSet<>();
            for (ConsumerRecord<String, String> record : records) {
                String guid = guidOf(record.key());
                if (seenGuids.add(guid)) {
                    assertPayload(record, stored.getId());
                }
            }
            assertThat(seenGuids).containsExactlyInAnyOrder("outage-1", "outage-2");
        }
    }

    @Test
    void poisonEventIsQuarantinedByRealKafkaAndCanBeRecovered() throws Exception {
        String invalidTopic = "bad topic ".repeat(25);
        outboxEvents.saveAndFlush(new OutboxEvent(1001L, TOPIC, "healthy-before", "{}"));
        OutboxEvent poison = outboxEvents.saveAndFlush(new OutboxEvent(4242L, invalidTopic, "poison-key", "{}"));
        outboxEvents.saveAndFlush(new OutboxEvent(1002L, TOPIC, "healthy-after", "{}"));

        relay.publishPending();

        OutboxEvent quarantined = outboxEvents.findById(poison.getId()).orElseThrow();
        assertThat(quarantined.getPoisonedAt()).isNotNull();
        assertThat(quarantined.getPublishedAt()).isNull();
        assertThat(quarantined.getLastError()).isNotBlank();
        assertThat(relay.observe().poisoned()).isGreaterThanOrEqualTo(1);
        assertThat(outboxEvents.findAll()).filteredOn(OutboxEvent::isPublished).extracting(OutboxEvent::getEventKey)
                .containsExactlyInAnyOrder("healthy-before", "healthy-after");

        quarantined.setTopic(TOPIC);
        outboxEvents.saveAndFlush(quarantined);
        assertThat(relay.requeuePoisoned()).isEqualTo(1);
        OutboxEvent recovered = outboxEvents.findById(poison.getId()).orElseThrow();
        assertThat(recovered.getPoisonedAt()).isNull();
        assertThat(recovered.getAttempts()).isZero();
        assertThat(recovered.getNextAttemptAt()).isNotNull();
        assertThat(recovered.getLastError()).isNull();

        try (KafkaConsumer<String, String> consumer = consumerAtEnd()) {
            relay.publishPending();
            assertThat(collect(consumer, 1)).extracting(ConsumerRecord::key).containsExactly("poison-key");
        }
        assertThat(outboxEvents.findById(poison.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void outboxUniqueConstraintRejectsDuplicateTopicAndEventKey() {
        outboxEvents.saveAndFlush(new OutboxEvent(1L, TOPIC, "unique-key", "{}"));

        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                () -> outboxEvents.saveAndFlush(new OutboxEvent(2L, TOPIC, "unique-key", "{}")));

        assertThat(failure).hasMessageContaining("uq_outbox_topic_event_key");
        assertThat(outboxEvents.count()).isEqualTo(1);
    }

    @Test
    void outboxUniqueViolationDuringIngestIsAFailureNotABenignDuplicate() {
        ca.feedline.domain.FeedSource source = sources.save(
                new ca.feedline.domain.FeedSource("Outbox Collision", feedUrl()));
        outboxEvents.saveAndFlush(new OutboxEvent(source.getId(), TOPIC, source.getId() + ":collide-1", "{}"));
        body.set(feed(entry("collide-1", "Collision", "https://example.test/collide-1")));

        int saved = poller.pollSingleSource(source());

        assertThat(saved).isZero();
        assertThat(items.findBySourceIdAndGuid(source.getId(), "collide-1")).isEmpty();
        assertThat(outboxEvents.findAll()).hasSize(1);
        assertThat(skips.findAll()).singleElement()
                .satisfies(skip -> assertThat(skip.getFailureClass()).isEqualTo("DATA_INTEGRITY"));
    }

    private long publishedCount() {
        return outboxEvents.findAll().stream().filter(event -> event.getPublishedAt() != null).count();
    }

    private void assertPayload(ConsumerRecord<String, String> record, Long sourceId) throws Exception {
        String guid = guidOf(record.key());
        JsonNode payload = mapper.readTree(record.value());
        Item item = items.findBySourceIdAndGuid(sourceId, guid).orElseThrow();
        assertThat(item.getPublishedAt()).isNotNull();
        assertThat(payload.get("itemId").asLong()).isEqualTo(item.getId());
        assertThat(payload.get("sourceId").asLong()).isEqualTo(sourceId);
        assertThat(payload.get("guid").asText()).isEqualTo(guid);
        assertThat(payload.get("title").asText()).isEqualTo(item.getTitle());
        assertThat(payload.get("link").asText()).isEqualTo(item.getLink());
        assertThat(payload.get("publishedAt").asText()).isEqualTo(item.getPublishedAt().toString());
        assertThat(payload.hasNonNull("ingestedAt")).isTrue();
    }

    private String guidOf(String key) {
        assertThat(key).as("Kafka record key").isNotNull().contains(":");
        return key.substring(key.indexOf(':') + 1);
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
            consumer.poll(Duration.ofMillis(200));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        Map<TopicPartition, Long> endOffsets = consumer.endOffsets(consumer.assignment());
        for (TopicPartition partition : consumer.assignment()) {
            assertThat(endOffsets).as("end offsets").containsKey(partition);
            long offset = endOffsets.get(partition);
            consumer.seek(partition, offset);
            assertThat(consumer.position(partition)).isEqualTo(offset);
        }
        return consumer;
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "feedline-outbox-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private List<ConsumerRecord<String, String>> collect(KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 30_000;
        while (records.size() < expected && System.currentTimeMillis() < deadline) {
            consumer.poll(Duration.ofMillis(300)).forEach(records::add);
        }
        return records;
    }

    private void serveFeed(HttpExchange exchange) throws IOException {
        byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
        if (etag.get() != null) {
            exchange.getResponseHeaders().set("ETag", etag.get());
        }
        if (lastModified.get() != null) {
            exchange.getResponseHeaders().set("Last-Modified", lastModified.get());
        }
        exchange.getResponseHeaders().set("Content-Type", "application/rss+xml; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Source source() {
        Source source = new Source("Kafka Outbox Fixture", feedUrl());
        source.setEnabled(true);
        return source;
    }

    private String feedUrl() {
        return "http://localhost:" + port + "/feed";
    }

    private String feed(String... entries) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .append("<rss version=\"2.0\"><channel><title>Fixture</title>")
                .append("<link>http://localhost</link><description>Fixture</description>");
        for (String entry : entries) {
            xml.append(entry);
        }
        return xml.append("</channel></rss>").toString();
    }

    private String entry(String guid, String title, String link) {
        return "<item><guid>" + guid + "</guid><link>" + link + "</link><title>" + title
                + "</title><pubDate>" + PUB_DATE + "</pubDate><description>summary</description></item>";
    }
}
