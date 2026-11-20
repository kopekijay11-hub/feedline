package ca.feedline.repository;

import ca.feedline.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
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
            ORDER BY id
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
            SELECT MIN(created_at) FROM outbox_events
            WHERE published_at IS NULL AND poisoned_at IS NULL
            """, nativeQuery = true)
    Instant oldestPendingCreatedAt();
}
