package ca.feedline.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "story_cluster_members")
public class StoryClusterMember {

    @Id
    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Column(name = "matched_rule", nullable = false, length = 16)
    private String matchedRule;

    @Column(name = "similarity", nullable = false)
    private double similarity;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    public StoryClusterMember() {
    }

    public Long getItemId() {
        return itemId;
    }

    public void setItemId(Long itemId) {
        this.itemId = itemId;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public void setClusterId(Long clusterId) {
        this.clusterId = clusterId;
    }

    public String getMatchedRule() {
        return matchedRule;
    }

    public void setMatchedRule(String matchedRule) {
        this.matchedRule = matchedRule;
    }

    public double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(double similarity) {
        this.similarity = similarity;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }
}
