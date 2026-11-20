package ca.feedline;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.OutboxRelayService;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class OutboxRelayRealKafkaConcurrencyTest {

    private static final String TOPIC = "feedline.outbox.concurrency";

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
        registry.add("feed.outbox.batch-size", () -> "4");
        registry.add("feed.outbox.initial-backoff-ms", () -> "100");
        registry.add("feed.outbox.max-backoff-ms", () -> "1000");
        registry.add("feed.scheduling-enabled", () -> "false");
    }

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService workers;

    @BeforeEach
    void setUp() throws Exception {
        outboxEvents.deleteAll();
        workers = Executors.newFixedThreadPool(2);
        ensureTopic();
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
        outboxEvents.deleteAll();
    }

    @Test
    void twoRelayWorkersPublishEachSeededRowExactlyOnceToRealKafka() throws Exception {
        int rows = 8;
        for (int i = 1; i <= rows; i++) {
            outboxEvents.saveAndFlush(new OutboxEvent((long) i, TOPIC, "concurrency-key-" + i, "{}"));
        }

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        OutboxRelayService first = new OutboxRelayService(outboxEvents, kafkaTemplate, 4, 5, 10, 100, 5_000,
                Clock.systemUTC());
        OutboxRelayService second = new OutboxRelayService(outboxEvents, kafkaTemplate, 4, 5, 10, 100, 5_000,
                Clock.systemUTC());

        CountDownLatch start = new CountDownLatch(1);
        Future<?> firstWorker = workers.submit(() -> {
            await(start);
            tx.executeWithoutResult(status -> first.publishPending());
        });
        Future<?> secondWorker = workers.submit(() -> {
            await(start);
            tx.executeWithoutResult(status -> second.publishPending());
        });
        start.countDown();
        firstWorker.get(60, TimeUnit.SECONDS);
        secondWorker.get(60, TimeUnit.SECONDS);

        assertThat(outboxEvents.findAll()).hasSize(rows)
                .allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());

        try (KafkaConsumer<String, String> consumer = consumerAtBeginning()) {
            List<ConsumerRecord<String, String>> records = collect(consumer, rows);
            assertThat(records).hasSize(rows);
            Set<String> keys = new java.util.HashSet<>();
            records.forEach(record -> keys.add(record.key()));
            assertThat(keys).hasSize(rows);
            for (int i = 1; i <= rows; i++) {
                assertThat(keys).contains("concurrency-key-" + i);
            }
        }
    }

    private KafkaConsumer<String, String> consumerAtBeginning() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties());
        consumer.subscribe(List.of(TOPIC));
        long deadline = System.currentTimeMillis() + 20_000;
        while (consumer.assignment().isEmpty() && System.currentTimeMillis() < deadline) {
            consumer.poll(java.time.Duration.ofMillis(200));
        }
        assertThat(consumer.assignment()).isNotEmpty();
        for (TopicPartition partition : consumer.assignment()) {
            consumer.seek(partition, 0);
        }
        return consumer;
    }

    private Properties consumerProperties() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "feedline-outbox-concurrency-" + UUID.randomUUID());
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

    private void ensureTopic() throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    // ignore if already exists
                }
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted before relay workers started", e);
        }
    }
}
