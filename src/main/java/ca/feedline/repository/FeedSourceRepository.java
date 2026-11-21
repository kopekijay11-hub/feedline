package ca.feedline.repository;

import ca.feedline.domain.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository for the feed registry. The {@link #findByUrl(String)} query is the
 * insert-if-absent lookup used by the poller when a feed row is missing.
 */
public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

    Optional<FeedSource> findByUrl(String url);

    @Query(value = """
            SELECT * FROM feed_sources
            WHERE enabled = true
              AND (lease_until IS NULL OR lease_until <= :now)
              AND (last_attempt_at IS NULL OR last_attempt_at <= :now)
            ORDER BY last_attempt_at NULLS FIRST, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<FeedSource> findClaimable(@Param("now") Instant now, @Param("limit") int limit);

    default List<FeedSource> findClaimable(Instant now) {
        return findClaimable(now, 100);
    }

    @Modifying
    @Query(value = "UPDATE feed_sources SET lease_owner = :owner, lease_until = :leaseUntil WHERE id = :id", nativeQuery = true)
    int claimLease(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") Instant leaseUntil);

    @Transactional
    @Query(value = """
            WITH candidates AS (
                SELECT id FROM feed_sources
                WHERE enabled = true
                  AND (lease_until IS NULL OR lease_until <= :now)
                  AND (last_attempt_at IS NULL OR last_attempt_at <= :now)
                ORDER BY last_attempt_at NULLS FIRST, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            ),
            updated AS (
                UPDATE feed_sources
                SET lease_owner = :owner,
                    lease_until = :leaseUntil,
                    last_attempt_at = :now
                WHERE id IN (SELECT id FROM candidates)
                RETURNING *
            )
            SELECT * FROM updated
            """, nativeQuery = true)
    List<FeedSource> claimDueSourcesAtomic(@Param("now") Instant now,
                                          @Param("owner") String owner,
                                          @Param("leaseUntil") Instant leaseUntil,
                                          @Param("limit") int limit);

    default List<FeedSource> claimDueSourcesAtomic(Instant now, String owner, Instant leaseUntil) {
        return claimDueSourcesAtomic(now, owner, leaseUntil, 100);
    }

    @Modifying
    @Transactional
    @Query(value = "UPDATE feed_sources SET lease_owner = NULL, lease_until = NULL WHERE id = :id", nativeQuery = true)
    int releaseLease(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query(value = "UPDATE feed_sources SET lease_owner = NULL, lease_until = NULL WHERE id = :id AND lease_owner = :owner", nativeQuery = true)
    int releaseLease(@Param("id") Long id, @Param("owner") String owner);
}
