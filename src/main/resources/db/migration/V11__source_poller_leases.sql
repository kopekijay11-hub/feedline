-- V11: multi-instance source polling leases.
--
-- Adds nullable lease columns so multiple poller replicas can claim disjoint
-- sets of due feed sources using FOR UPDATE SKIP LOCKED. Historical rows start
-- unleased (NULL) and are immediately claimable.

ALTER TABLE feed_sources ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128);
ALTER TABLE feed_sources ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_feed_sources_claimable
    ON feed_sources (last_attempt_at NULLS FIRST, id)
    WHERE enabled = true;
