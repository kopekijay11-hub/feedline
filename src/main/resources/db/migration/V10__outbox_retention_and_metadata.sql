CREATE INDEX IF NOT EXISTS idx_outbox_events_published_cleanup
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS replayed_count INT NOT NULL DEFAULT 0;

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS schema_version INT NOT NULL DEFAULT 1;
