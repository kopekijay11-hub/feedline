-- V2: Add ETag and Last-Modified columns to feed_sources for conditional GET support.
--
-- These columns store the HTTP validators returned by the feed server, used for
-- sending If-None-Match and If-Modified-Since headers in subsequent requests.
-- Both are nullable text to accommodate various server responses.

ALTER TABLE feed_sources ADD COLUMN etag TEXT;
ALTER TABLE feed_sources ADD COLUMN last_modified TEXT;
