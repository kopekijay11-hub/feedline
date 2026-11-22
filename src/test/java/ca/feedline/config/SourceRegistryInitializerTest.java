package ca.feedline.config;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceRegistryInitializerTest {

    @Test
    void existingSourceMetadataIsSyncedWithConfiguration() {
        FeedProperties.Source configured = new FeedProperties.Source("New name", "http://example.test/feed");
        configured.setEnabled(false);
        FeedProperties props = new FeedProperties();
        props.setSources(List.of(configured));

        FeedSource existing = new FeedSource("Old name", "http://example.test/feed");
        existing.setId(7L);
        existing.setEnabled(true);
        FeedSourceRepository repo = mock(FeedSourceRepository.class);
        when(repo.insertIfAbsent("New name", "http://example.test/feed", false))
                .thenReturn(0);
        when(repo.findByUrl("http://example.test/feed")).thenReturn(Optional.of(existing));

        new SourceRegistryInitializer(props, repo).run(mock(ApplicationArguments.class));

        verify(repo).save(existing);
        assertThat(existing.getName()).isEqualTo("New name");
        assertThat(existing.isEnabled()).isFalse();
    }

    @Test
    void insertIfAbsentReturnsNewSourceOnEmptyDatabase() {
        FeedProperties.Source configured = new FeedProperties.Source("Test Feed", "http://example.test/feed");
        configured.setEnabled(true);
        FeedProperties props = new FeedProperties();
        props.setSources(List.of(configured));

        FeedSource inserted = new FeedSource("Test Feed", "http://example.test/feed");
        inserted.setId(42L);
        inserted.setEnabled(true);
        FeedSourceRepository repo = mock(FeedSourceRepository.class);
        when(repo.insertIfAbsent("Test Feed", "http://example.test/feed", true))
                .thenReturn(1);
        when(repo.findByUrl("http://example.test/feed"))
                .thenReturn(Optional.of(inserted));

        SourceRegistryInitializer initializer = new SourceRegistryInitializer(props, repo);
        initializer.run(mock(ApplicationArguments.class));

        assertThat(initializer.isSeedingComplete()).isTrue();
        verify(repo).insertIfAbsent("Test Feed", "http://example.test/feed", true);
    }

    @Test
    void concurrentInitializationIsIdempotent() throws InterruptedException {
        // Simulates two threads trying to seed the same source concurrently
        // against an empty database. Both should succeed without throwing
        // SQLState 23505 (unique constraint violation).
        FeedProperties.Source configured = new FeedProperties.Source("Concurrent Feed", "http://example.test/concurrent");
        configured.setEnabled(true);
        FeedProperties props = new FeedProperties();
        props.setSources(List.of(configured));

        FeedSourceRepository repo = mock(FeedSourceRepository.class);

        // First thread wins the insert (returns 1), second gets 0 (insert ignored)
        FeedSource winner = new FeedSource("Concurrent Feed", "http://example.test/concurrent");
        winner.setId(1L);
        when(repo.insertIfAbsent("Concurrent Feed", "http://example.test/concurrent", true))
                .thenReturn(1, 0);
        when(repo.findByUrl("http://example.test/concurrent"))
                .thenReturn(Optional.of(winner));

        SourceRegistryInitializer init1 = new SourceRegistryInitializer(props, repo);
        SourceRegistryInitializer init2 = new SourceRegistryInitializer(props, repo);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger(0);

        Thread t1 = new Thread(() -> {
            try {
                start.await();
                init1.run(mock(ApplicationArguments.class));
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                start.await();
                init2.run(mock(ApplicationArguments.class));
            } catch (Exception e) {
                errors.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        t1.start();
        t2.start();
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        assertThat(init1.isSeedingComplete()).isTrue();
        assertThat(init2.isSeedingComplete()).isTrue();
    }
}
