-- V13: source health backoff state (M5 Phase 3).
--
-- Adds per-source scheduling state so failing or rate-limited feeds back off
-- predictably instead of being reclaimed on every scheduler tick. next_poll_at
-- is the earliest instant the source may be claimed again; last_error_at and
-- last_error hold the timestamp and a bounded, secret-free summary for the
-- health API. Existing rows start with NULL (immediately claimable).

ALTER TABLE feed_sources ADD COLUMN IF NOT EXISTS next_poll_at TIMESTAMP;
ALTER TABLE feed_sources ADD COLUMN IF NOT EXISTS last_error_at TIMESTAMP;
ALTER TABLE feed_sources ADD COLUMN IF NOT EXISTS last_error TEXT;

CREATE INDEX IF NOT EXISTS idx_feed_sources_next_poll
    ON feed_sources (next_poll_at)
    WHERE enabled = true;