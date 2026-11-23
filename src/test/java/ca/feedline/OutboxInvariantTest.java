package ca.feedline;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.domain.OutboxEvent;
import ca.feedline.dto.ItemIngestedEvent;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.ItemIngestFailure;
import ca.feedline.service.ItemIngestService;
import ca.feedline.service.OutboxRelayService;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "feed.scheduling-enabled=false")
class OutboxInvariantTest {

    private static final String TOPIC = "feedline.outbox.invariant." + UUID.randomUUID().toString().replace("-", "");
    private static final int ROWS = 5;

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
        registry.add("feed.outbox.initial-backoff-ms", () -> "1");
        registry.add("feed.outbox.max-backoff-ms", () -> "10");
        registry.add("feed.outbox.max-attempts", () -> "5");
    }

    @Autowired
    private FeedSourceRepository sources;

    @Autowired
    private ItemRepository items;

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private ItemIngestService itemIngestService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaTemplate<String, String> realKafkaTemplate;

    private FeedSource source;
    private final Set<String> dbEventKeys = new HashSet<>();

    @BeforeEach
    void setUp() throws Exception {
        outboxEvents.deleteAll();
        items.deleteAll();
        sources.deleteAll();
        dbEventKeys.clear();
        ensureTopic();
        source = new FeedSource("src", "https://example.test/feed.xml");
        sources.save(source);

        // Seed ROWS items + outbox rows (the committed set).
        for (int i = 1; i <= ROWS; i++) {
            String guid = "guid-" + i;
            items.save(new Item(source, guid, "https://example.test/" + i, "title-" + i, "summary", null,
                    Instant.now()));
            // fetch saved item id
        }
        // Re-read to get ids
        List<Item> saved = items.findBySourceIdOrderByFetchedAtDesc(source.getId());
        for (Item it : saved) {
            String eventKey = source.getId() + ":" + it.getGuid();
            dbEventKeys.add(eventKey);
            OutboxEvent ev = new OutboxEvent(it.getId(), TOPIC, eventKey, "{\"eventKey\":\"" + eventKey + "\"}");
            outboxEvents.saveAndFlush(ev);
        }
        assertThat(dbEventKeys).hasSize(ROWS);
    }

    @AfterEach
    void tearDown() {
        outboxEvents.deleteAll();
        items.deleteAll();
        sources.deleteAll();
    }

    @Test
    void outboxInvariantHoldsAcrossFaults() throws Exception {
        // Fault (a): DB failure mid-ingest must not leave partial rows.
        long beforeItems = items.count();
        long beforeOutbox = outboxEvents.count();
        FeedSource transientSource = new FeedSource("transient", "https://example.test/transient");
        SyndEntry entry = new SyndEntryImpl();
        entry.setUri("guid-transient");
        entry.setTitle("t");
        entry.setLink("https://example.test/transient");
        try {
            itemIngestService.ingest(transientSource, entry, Instant.now());
        } catch (ItemIngestFailure expected) {
            // expected
        }
        assertThat(items.count()).isEqualTo(beforeItems);
        assertThat(outboxEvents.count()).isEqualTo(beforeOutbox);

        try (KafkaConsumer<String, String> consumer = consumerFromBeginning()) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            // Fault (b): broker down at relay time.
            KafkaTemplate<String, String> deadBroker = deadKafkaTemplate();
            OutboxRelayService deadRelay = new OutboxRelayService(outboxEvents, deadBroker, 50, 5, 1, 10, 5_000,
                    Clock.systemUTC());
            tx.executeWithoutResult(status -> deadRelay.publishPending());
            // rows remain unpublished (not marked)
            assertThat(outboxEvents.findAll()).allSatisfy(e -> assertThat(e.getPublishedAt()).isNull());

            // allow backoff to elapse
            Thread.sleep(50);

            // Fault (c): relay killed between Kafka ack and mark-published (crashing template).
            OutboxRelayService crashingRelay = new OutboxRelayService(outboxEvents, new CrashingKafkaTemplate(realKafkaTemplate),
                    50, 5, 1, 10, 5_000, Clock.systemUTC());
            try {
                tx.executeWithoutResult(status -> crashingRelay.publishPending());
            } catch (RuntimeException ignored) {
                // simulated kill
            }

            // Real relay completes remaining work (and re-sends the crashed one).
            Thread.sleep(50);
            OutboxRelayService realRelay = new OutboxRelayService(outboxEvents, realKafkaTemplate, 50, 5, 1, 10, 5_000,
                    Clock.systemUTC());
            tx.executeWithoutResult(status -> realRelay.publishPending());

            // Collect consumed messages.
            List<ConsumerRecord<String, String>> records = collect(consumer, ROWS + 1);
            Set<String> consumedKeys = new HashSet<>();
            for (ConsumerRecord<String, String> r : records) {
                consumedKeys.add(r.key());
            }

            // Items in DB == distinct consumed eventKeys; consumed count >= distinct count.
            assertThat(consumedKeys).isEqualTo(dbEventKeys);
            assertThat(records.size()).isGreaterThanOrEqualTo(consumedKeys.size());
        }
    }

    private KafkaConsumer<String, String> consumerFromBeginning() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties());
        consumer.subscribe(List.of(TOPIC));
        long deadline = System.currentTimeMillis() + 20_000;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(java.time.Duration.ofMillis(200));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        for (TopicPartition tp : consumer.assignment()) {
            consumer.seek(tp, 0);
        }
        return consumer;
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "feedline-outbox-invariant-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    private List<ConsumerRecord<String, String>> collect(KafkaConsumer<String, String> consumer, int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 40_000;
        while (records.size() < expected && System.currentTimeMillis() < deadline) {
            consumer.poll(java.time.Duration.ofMillis(300)).forEach(records::add);
        }
        return records;
    }

    private KafkaTemplate<String, String> deadKafkaTemplate() {
        KafkaTemplate<String, String> t = new KafkaTemplate<>(realKafkaTemplate.getProducerFactory()) {
            @Override
            public java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> send(
                    org.apache.kafka.clients.producer.ProducerRecord<String, String> record) {
                throw new RuntimeException("broker down");
            }
        };
        return t;
    }

    private void ensureTopic() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    // ignore
                }
            }
        }
    }

    private static final class CrashingKafkaTemplate extends KafkaTemplate<String, String> {
        private final KafkaTemplate<String, String> delegate;
        private final AtomicBoolean crashed = new AtomicBoolean(false);

        CrashingKafkaTemplate(KafkaTemplate<String, String> delegate) {
            super(delegate.getProducerFactory());
            this.delegate = delegate;
        }

        @Override
        public CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> send(
                ProducerRecord<String, String> record) {
            CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> f = delegate.send(record);
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (crashed.compareAndSet(false, true)) {
                throw new RuntimeException("simulated crash between kafka ack and mark-published");
            }
            return f;
        }
    }
}
