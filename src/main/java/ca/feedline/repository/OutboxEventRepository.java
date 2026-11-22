package ca.feedline.repository;

import ca.feedline.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NULL
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            ORDER BY next_attempt_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPublishable(@Param("limit") int limit, @Param("now") Instant now);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NOT NULL
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPoisoned(@Param("limit") int limit);

    @Query(value = """
            SELECT COUNT(*) FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NULL
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
            """, nativeQuery = true)
    long countDue(@Param("now") Instant now);

    @Query(value = """
            SELECT COUNT(*) FROM outbox_events
            WHERE published_at IS NULL AND poisoned_at IS NULL
            """, nativeQuery = true)
    long countPublishable();

    @Query(value = """
            SELECT COUNT(*) FROM outbox_events
            WHERE published_at IS NULL AND poisoned_at IS NOT NULL
            """, nativeQuery = true)
    long countPoisoned();

    @Query(value = """
            SELECT COUNT(*) FROM outbox_events
            WHERE published_at IS NOT NULL
            """, nativeQuery = true)
    long countPublished();

    @Query(value = """
            SELECT MIN(created_at) FROM outbox_events
            WHERE published_at IS NULL AND poisoned_at IS NULL
            """, nativeQuery = true)
    Instant oldestPendingCreatedAt();

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                ORDER BY published_at, id
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                ORDER BY published_at, id
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deletePublishedBeforeCutoff(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                ORDER BY published_at, id
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deletePublishedBeforeBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                ORDER BY published_at, id
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deletePublishedBatch(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                ORDER BY published_at, id
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteExpiredPublished(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE id IN (
                SELECT id FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < :cutoff
                ORDER BY published_at, id
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int deleteOldPublished(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

    // convenience aliases for count queries
    @Query(value = """
            SELECT COUNT(*) FROM outbox_events
            WHERE published_at IS NULL AND poisoned_at IS NULL
            """, nativeQuery = true)
    long countPending();

    // admin poisoned listing helpers
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NOT NULL
              AND (:topic IS NULL OR topic = :topic)
            ORDER BY poisoned_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<OutboxEvent> findPoisonedOrdered(@Param("topic") String topic, @Param("limit") int limit, @Param("offset") int offset);

    @Query(value = """
            SELECT COUNT(*) FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NOT NULL
              AND (:topic IS NULL OR topic = :topic)
            """, nativeQuery = true)
    long countPoisonedFiltered(@Param("topic") String topic);

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NOT NULL
              AND (:topic IS NULL OR topic = :topic)
            ORDER BY poisoned_at ASC, id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findOldestPoisoned(@Param("topic") String topic, @Param("limit") int limit);
}
