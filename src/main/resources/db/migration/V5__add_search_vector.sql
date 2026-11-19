-- V5: Full-text search vector over item title and summary (M2B API).
--
-- The column is GENERATED ALWAYS ... STORED, so Postgres maintains it on every
-- insert/update; application code and triggers never touch it, and it cannot go
-- stale relative to title/summary. Language configuration is 'english'.
-- Queried by GET /api/v1/items via the ?q= filter.

ALTER TABLE items
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(summary, ''))
    ) STORED;

CREATE INDEX idx_items_search_vector ON items USING GIN (search_vector);
