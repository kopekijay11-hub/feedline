-- V14: story clustering (annotation only; never suppress/hide/merge items).

CREATE TABLE story_clusters (
    id BIGSERIAL PRIMARY KEY,
    representative_item_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    first_seen_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_story_clusters_representative
        FOREIGN KEY (representative_item_id) REFERENCES items (id) ON DELETE CASCADE
);

CREATE TABLE story_cluster_members (
    cluster_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    matched_rule VARCHAR(16) NOT NULL,
    similarity DOUBLE PRECISION NOT NULL,
    joined_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_story_cluster_members PRIMARY KEY (item_id),
    CONSTRAINT uq_story_cluster_members_item UNIQUE (item_id),
    CONSTRAINT fk_story_cluster_members_cluster
        FOREIGN KEY (cluster_id) REFERENCES story_clusters (id) ON DELETE CASCADE,
    CONSTRAINT fk_story_cluster_members_item
        FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE
);

CREATE INDEX idx_story_cluster_members_cluster ON story_cluster_members (cluster_id);
