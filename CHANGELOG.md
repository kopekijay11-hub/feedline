# Changelog

## M5 Phase 4 — End-to-end poll-to-replay-to-cleanup integration, deployment & config hardening, docs sync.
- Added `FeedlineLifecycleE2ETest`: full lifecycle against real PostgreSQL and real Kafka (poll/ingest with robots stub, atomic outbox emission, lease release, transient relay failures to poison quarantine, V12 `last_attempt_at`/sanitized `last_error`, admin 401/200, replay to real Kafka with preserved `eventKey`/payload/UTF-8 headers, health, fetchedFrom/fetchedTo item filtering, and retention cleanup preserving pending/poisoned rows).
- Added point-in-time `fetchedFrom`/`fetchedTo` query filters to `GET /api/v1/items`.
- Hardened deployment defaults: explicit bounded HikariCP pool settings, documented JVM heap/env vars and operational runbook checks; actuator exposure remains `health,info`, admin token required for `/api/v1/admin/**`, graceful shutdown/bounded timeouts preserved.
- Synced README, DECISIONS, HANDOFF, and CHANGELOG for V1–V13 schema coverage, M5 deliverables, endpoints, config knobs, and verification counts.
- Verification: `mvn clean verify` — `142` tests run / `142` passed (`0` failures, `0` errors, `0` skipped); SpotBugs `BugInstance size is 0`, `Error size is 0` (`failOnError=true`).

## M5 Phase 3 — Source health/backoff alignment, graceful shutdown & metrics coverage.
- Added `V13__source_health_backoff.sql`: `next_poll_at`, `last_error_at`, and bounded `last_error` per source
- Consecutive fetch/parse/network failures now increment `consecutive_failures`, set `last_error_at`/`last_error`, and schedule exponential backoff (`feed.poller.backoff-base`, `feed.poller.max-backoff`)
- Upstream `429`/`503` `Retry-After` is parsed and bounded (1h); the poller treats rate-limited sources as `BACKOFF` and defers re-claiming via `next_poll_at`
- A clean poll or 304 clears failure, backoff, and error state
- `/api/v1/sources/health` exposes `state`, `nextPollAt`, `lastErrorAt`, and a `PoisonErrorSanitizer`-redacted `lastError`
- Added graceful shutdown hooks: the poller stops claiming, waits a bounded in-flight window, and releases all leases owned by the worker; relay/cleanup stop accepting new work
- Added `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase`, and bounded scheduler shutdown settings
- Added low-cardinality metrics: `feedline.poller.poll.total{outcome=success|not_modified|rate_limited|error}`, `feedline.sources.health{state=...}`, `feedline.sources.active.count`, and `feedline.shutdown.total{outcome=...}`
- Added unit/seam coverage for backoff progression, `429`/`503` handling, recovery, sanitized health errors, lease release, and metric tag safety (141 tests total)

## M5 Phase 2 — Outbox relay batch/partition tuning, poison diagnostics & relay tests.
- Added bounded `feed.outbox.*` binding through `FeedProperties.Outbox` with clamping for batch size, attempts, backoff, and send timeout
- Relay claims publishable rows by `(next_attempt_at, id)` with `FOR UPDATE SKIP LOCKED`, keeping batches bounded by the configured limit
- Kafka record key and `eventKey` header remain the deterministic `sourceId:guid`; retries and admin replays preserve topic, payload, and schema version
- `V12__outbox_last_attempt_at.sql` adds `last_attempt_at` for attempt diagnostics
- Poisoned-event listing now exposes `attempts`, `replayedCount`, `poisonedAt`, `lastAttemptAt`, `nextAttemptAt`, and a sanitized, 500-character-capped `lastError` with secrets redacted
- Batch replay reports `skippedIds` and locks candidate rows with `FOR UPDATE SKIP LOCKED`
- Added unit, concurrency, real-Kafka partition, and admin seam coverage (132 tests total)

## M5 Phase 1 — Outbox event schema, headers & deterministic eventKey.
- Added schema/version identifiers, eventKey, eventType, occurredAt, and sourceId to outbox payloads and Kafka headers
- Documented downstream consumer idempotency contract

All notable changes to Feedline are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/), and versions correspond to project milestones.

## [0.3.0] — 2026-11-20

Milestone 3 — Kafka outbox and post-merge polish (merged to `main` at `f598f1f`).

- Transactional outbox: `V9__outbox_events.sql` stores events atomically with newly ingested items; no historical backfill
- Outbox relay with `FOR UPDATE SKIP LOCKED`, stored-topic publishing, bounded exponential backoff, poison quarantine/requeue, pending/due/poison/lag observability hooks, and atomic poison replay
- Kafka producer `delivery.timeout.ms` derived from the relay send wait; retry eligibility uses the application clock
- Unique-constraint duplicate handling scoped to `uq_items_source_guid`; outbox `uq_outbox_topic_event_key` violations are real failures
- Fetch hardening: per-hop robots validation, lock-free per-host waiting, response charset handling, and DNS address rechecks
- Additional coverage: two-worker relay claiming, real-Kafka poison quarantine and outbox uniqueness, and stub-server teardown
- Documentation refreshed for the merged M3 state; Failsafe removed as an inert plugin

## [0.2.0] — 2026-11-18

Milestone 2 — API, health, and hardening.

- Keyset pagination and full-text search on `/api/v1/items` with RFC 7807 problem responses
- Per-source health surface plus Actuator liveness/readiness split and poll-cycle heartbeat
- Per-item `REQUIRES_NEW` ingest isolation, skip recording/retention, and fail-closed SSRF fetch policy
- Docker Compose quick start extended with a KRaft Kafka broker
- SpotBugs exclusions rationalized; static analysis remains enforced at `verify`

## [0.1.0] — 2026-11-17

Milestone 1 — the ingestion core.

- Scheduled RSS/Atom poller (fixed delay) with primary/fallback feed handling
- Rome-based feed parsing and normalization into a JPA `Item` entity
- Idempotent storage: a unique `(source_id, guid)` constraint makes re-polls insert-if-absent
- PostgreSQL 16 schema managed by Flyway from the first migration
- Unit tests against a captured real feed, plus Testcontainers integration tests against real PostgreSQL
- SpotBugs static analysis wired into the Maven build
- Docker Compose quick start (PostgreSQL 16)

## [0.4.0] — 2026-11-20

Milestone 4 Phase 1 — Observability & outbox housekeeping.

- `V10__outbox_retention_and_metadata.sql`: partial cleanup index plus `replayed_count` and `schema_version`
- Bounded outbox retention cleanup (batched, published rows only; pending and poisoned rows preserved)
- Micrometer metrics for outbox pending/poisoned/oldest-pending-age, event outcomes, ingest outcomes, and fetch outcomes/duration
- Poison replay increments `replayed_count` and `feedline.outbox.events.total{outcome="replayed"}`

## [0.5.0] — 2026-11-20

Milestone 4 Phase 2 — Operator admin API & security boundary.

- Header-token auth boundary for `/api/v1/admin/**` (constant-time comparison)
- Admin poisoned outbox listing, single replay, and batch replay endpoints
- RFC 7807 problem+json for 401/403/404/409/400

## [0.6.0] — 2026-11-20

Milestone 4 Phase 3 — Multi-instance source leasing.

- `V11__source_poller_leases.sql`: nullable `lease_owner`/`lease_until` plus partial claim index
- `FOR UPDATE SKIP LOCKED` claim-and-release leasing with configurable duration and worker id
- Expired-lease crash recovery and contention metrics

## [0.7.0] — 2026-11-20

Milestone 4 Phase 4 — Robots cache & wildcard matching, normalizer sanitization & caps, query cursor hardening.

- Bounded LRU robots cache with configurable max entries and TTL
- Bot-specific user-agent group selection with wildcard fallback
- Safe `*` wildcard and `$` anchor path matching
- `FeedNormalizer` HTML stripping, entity decoding, configurable field caps, Atom/RSS fallbacks, deterministic synthetic GUIDs
- Query cursor pagination keeps active filters bound
