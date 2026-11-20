package ca.feedline.repository;

import ca.feedline.domain.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Pageable pageable);

    @Modifying
    @Query(value = "UPDATE outbox_events SET published_at = clock_timestamp() WHERE id = :id", nativeQuery = true)
    void markPublished(@Param("id") Long id);
}
