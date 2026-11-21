package ca.feedline;

import ca.feedline.domain.OutboxEvent;
import ca.feedline.repository.OutboxEventRepository;
import ca.feedline.service.OutboxRelayService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxRelayConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.16-alpine")
            .withDatabaseName("feedline")
            .withUsername("feedline")
            .withPassword("feedline");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("feed.poll-delay", () -> "86400000");
        registry.add("feed.scheduling-enabled", () -> "false");
        registry.add("feed.outbox.enabled", () -> "false");
    }

    @Autowired
    private OutboxEventRepository outboxEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        outboxEvents.deleteAll();
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        workers.shutdownNow();
        outboxEvents.deleteAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoRelayWorkersClaimEachRowExactlyOnceWithSkipLocked() throws Exception {
        for (int i = 1; i <= 4; i++) {
            outboxEvents.saveAndFlush(new OutboxEvent((long) i, "topic", "key-" + i, "{}"));
        }

        CountDownLatch sendsRendezvous = new CountDownLatch(2);
        Set<String> sentKeys = ConcurrentHashMap.newKeySet();
        AtomicInteger sendCount = new AtomicInteger();
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            sentKeys.add(record.key());
            sendCount.incrementAndGet();
            sendsRendezvous.countDown();
            assertThat(sendsRendezvous.await(10, TimeUnit.SECONDS))
                    .as("both workers must reach their send while holding distinct row locks").isTrue();
            CompletableFuture<SendResult<String, String>> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        });

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        OutboxRelayService first = new OutboxRelayService(outboxEvents, kafka, 2, 5, 10, 100, 5_000,
                Clock.systemUTC());
        OutboxRelayService second = new OutboxRelayService(outboxEvents, kafka, 2, 5, 10, 100, 5_000,
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
        firstWorker.get(30, TimeUnit.SECONDS);
        secondWorker.get(30, TimeUnit.SECONDS);

        assertThat(sendCount.get()).isEqualTo(4);
        assertThat(sentKeys).containsExactlyInAnyOrder("key-1", "key-2", "key-3", "key-4");
        assertThat(outboxEvents.findAll()).hasSize(4)
                .allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());
        assertThat(first.publishedCount() + second.publishedCount()).isEqualTo(4);
    }

    @Test
    void singleRelayBatchIsBoundedByConfiguredBatchSize() {
        for (int i = 1; i <= 5; i++) {
            outboxEvents.saveAndFlush(new OutboxEvent((long) i, "topic", "bounded-" + i, "{}"));
        }
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.any(ProducerRecord.class))).thenAnswer(invocation -> {
            CompletableFuture<SendResult<String, String>> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        });
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        OutboxRelayService relay = new OutboxRelayService(outboxEvents, kafka, 2, 5, 10, 100, 5_000,
                Clock.systemUTC());

        tx.executeWithoutResult(status -> relay.publishPending());

        assertThat(outboxEvents.findAll()).filteredOn(event -> event.getPublishedAt() != null).hasSize(2);
        assertThat(outboxEvents.findAll()).filteredOn(event -> event.getPublishedAt() == null).hasSize(3);
    }

    @Test
    void fourWorkersWithBatchSizeTwoPublishEveryRowExactlyOnce() throws Exception {
        int rows = 8;
        for (int i = 1; i <= rows; i++) {
            outboxEvents.saveAndFlush(new OutboxEvent((long) i, "topic", "single-" + i, "{}"));
        }
        AtomicInteger sendCount = new AtomicInteger();
        Set<String> sentKeys = ConcurrentHashMap.newKeySet();
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(org.mockito.ArgumentMatchers.any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            sentKeys.add(record.key());
            sendCount.incrementAndGet();
            CompletableFuture<SendResult<String, String>> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        });
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        List<OutboxRelayService> relays = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            relays.add(new OutboxRelayService(outboxEvents, kafka, 2, 5, 10, 100, 5_000, Clock.systemUTC()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (OutboxRelayService relay : relays) {
                futures.add(pool.submit(() -> {
                    await(start);
                    tx.executeWithoutResult(status -> relay.publishPending());
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sendCount.get()).isEqualTo(rows);
        assertThat(sentKeys).hasSize(rows);
        assertThat(outboxEvents.findAll()).hasSize(rows)
                .allSatisfy(event -> assertThat(event.getPublishedAt()).isNotNull());
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
