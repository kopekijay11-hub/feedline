package ca.feedline.repository;

import ca.feedline.domain.FeedSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for the feed registry. The {@link #findByUrl(String)} query is the
 * insert-if-absent lookup used by the poller when a feed row is missing.
 */
public interface FeedSourceRepository extends JpaRepository<FeedSource, Long> {

    Optional<FeedSource> findByUrl(String url);
}
