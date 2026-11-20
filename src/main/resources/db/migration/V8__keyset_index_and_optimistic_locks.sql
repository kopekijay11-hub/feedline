-- V8: keyset pagination index, optimistic locking, and skip retention support.
--
-- V7 is intentionally left for the parked M3 outbox/Kafka feature branch so
-- the two migrations can coexist if that branch is merged later.

CREATE INDEX IF NOT EXISTS idx_items_fetched_at_id
    ON items (fetched_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_ingest_skips_last_skipped_at
    ON ingest_skips (last_skipped_at);

ALTER TABLE feed_sources
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ingest_skips
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
