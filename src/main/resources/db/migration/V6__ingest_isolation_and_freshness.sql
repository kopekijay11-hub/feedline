CREATE TABLE ingest_skips (
    source_id BIGINT NOT NULL,
    guid VARCHAR(512) NOT NULL,
    failure_class VARCHAR(64) NOT NULL,
    first_skipped_at TIMESTAMP NOT NULL,
    last_skipped_at TIMESTAMP NOT NULL,
    occurrences INTEGER NOT NULL DEFAULT 1,
    PRIMARY KEY (source_id, guid),
    CONSTRAINT fk_ingest_skips_source FOREIGN KEY (source_id)
        REFERENCES feed_sources (id) ON DELETE CASCADE,
    CONSTRAINT ck_ingest_skips_occurrences CHECK (occurrences > 0)
);

CREATE INDEX idx_ingest_skips_source_last
    ON ingest_skips (source_id, last_skipped_at DESC);

ALTER TABLE items
    ADD CONSTRAINT ck_items_title_max_length CHECK (char_length(title) <= 1000);

ALTER TABLE feed_sources ADD COLUMN last_new_item_at TIMESTAMP;
