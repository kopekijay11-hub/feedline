-- V3: Enforce uniqueness of feed source URLs as intended by the domain model.
ALTER TABLE feed_sources
    ADD CONSTRAINT uq_feed_sources_url UNIQUE (url);
