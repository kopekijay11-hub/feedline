CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id BIGINT NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(1024) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    published_at TIMESTAMP WITH TIME ZONE NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    last_error TEXT NULL,
    poisoned_at TIMESTAMP WITH TIME ZONE NULL,
    CONSTRAINT uq_outbox_topic_event_key UNIQUE (topic, event_key)
);

CREATE INDEX idx_outbox_publishable ON outbox_events (next_attempt_at, id)
    WHERE published_at IS NULL AND poisoned_at IS NULL;
