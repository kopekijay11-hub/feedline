package ca.feedline.repository;

import ca.feedline.domain.StoryClusterMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface StoryClusterMemberRepository extends JpaRepository<StoryClusterMember, Long> {

    Optional<StoryClusterMember> findByItemId(Long itemId);

    List<StoryClusterMember> findByClusterId(Long clusterId);

    @Modifying
    @Transactional
    @Query("DELETE FROM StoryClusterMember m WHERE m.clusterId = :clusterId")
    int deleteByClusterId(@Param("clusterId") Long clusterId);

    @Modifying
    @Transactional
    @Query("UPDATE StoryClusterMember m SET m.clusterId = :target WHERE m.clusterId = :source")
    int reassignCluster(@Param("source") Long source, @Param("target") Long target);
}
