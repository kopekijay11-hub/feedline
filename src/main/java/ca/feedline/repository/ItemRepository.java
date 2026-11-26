package ca.feedline.repository;

import ca.feedline.domain.Item;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for the {@code items} table.
 *
 * <p>The unique constraint on {@code (source_id, guid)} is the single source of
 * truth for "insert-if-absent" feed item semantics.
 */
public interface ItemRepository extends JpaRepository<Item, Long> {

    List<Item> findBySourceIdOrderByFetchedAtDesc(Long sourceId);

    long countBySourceIdAndGuid(Long sourceId, String guid);

    Optional<Item> findBySourceIdAndGuid(Long sourceId, String guid);

    Optional<Item> findByGuid(String guid);

    @Query("SELECT i FROM Item i WHERE i.source.id IN :sourceIds ORDER BY COALESCE(i.publishedAt, i.fetchedAt) DESC, i.id DESC")
    List<Item> findNewestBySourceIds(@Param("sourceIds") List<Long> sourceIds, Pageable pageable);
}
