-- V1: base schema for Feedline.
--
-- The unique (source_id, guid) pair on `items` is the enforcement mechanism of
-- the feed poller's insert-if-absent semantics: a second save of the same
-- (source, guid) is rejected and the first row keeps its identity.

CREATE TABLE feed_sources (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(1024) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE items (
    id BIGSERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL,
    guid VARCHAR(512) NOT NULL,
    link VARCHAR(1024) NOT NULL,
    title TEXT NOT NULL,
    summary TEXT,
    published_at TIMESTAMP,
    fetched_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_items_source FOREIGN KEY (source_id) REFERENCES feed_sources (id) ON DELETE CASCADE,
    CONSTRAINT uq_items_source_guid UNIQUE (source_id, guid)
);
