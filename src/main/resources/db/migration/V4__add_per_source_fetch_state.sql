-- V4: Per-source fetch state for health reporting (M2B).
ALTER TABLE feed_sources ADD COLUMN last_attempt_at TIMESTAMP;
ALTER TABLE feed_sources ADD COLUMN last_success_at TIMESTAMP;
ALTER TABLE feed_sources ADD COLUMN last_error_class VARCHAR(64);
ALTER TABLE feed_sources ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0;
