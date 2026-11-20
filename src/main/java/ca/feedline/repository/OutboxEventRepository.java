package ca.feedline.repository;

import ca.feedline.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
              AND poisoned_at IS NULL
              AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp())
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPublishable(@Param("limit") int limit);
}
