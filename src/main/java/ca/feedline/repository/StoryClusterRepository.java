package ca.feedline.repository;

import ca.feedline.domain.StoryCluster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryClusterRepository extends JpaRepository<StoryCluster, Long> {
}
