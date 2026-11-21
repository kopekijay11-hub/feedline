# Feedline — Handoff

## Current branch
- Active branch: `main`; `origin/main` in sync; working tree clean prior to this change.
- Base HEAD: `be97405` (`main` @ `be97405`, M3 + M4 Phases 1–4 merged).
- Migration state: `V1`–`V11` ship on `main` (`V7` skipped; `V10` outbox retention/replay metadata; `V11` source poller leases).
- Verification baseline: `111/111` tests passing, `0` SpotBugs findings (`failOnError=true`).

## M3 hardening
- Renamed the outbox migration from `V7` to `V9`. It now runs after `V8` on databases that already applied `V8`, which is required with `validate-on-migrate: true` and the default `out-of-order: false`.
- `V9` creates `outbox_events` with retry metadata (`attempts`, `next_attempt_at`, `last_error`, `poisoned_at`) and a unique `(topic, event_key)` constraint.
- `OutboxRelayService` publishes the stored event topic (not just the configured default), locks rows with `FOR UPDATE SKIP LOCKED`, records bounded exponential backoff on transient failures, and quarantines permanent failures or retry exhaustion as poison events. It stops the batch on the first transient failure so one outage cannot stall all 50 rows behind the configured send wait, but it continues past poison rows.
- Retry eligibility and backoff deadlines are evaluated with the same application `Clock`: the relay passes `clock.instant()` into the publishable query instead of relying on the database's `clock_timestamp()`. Poison rows can be requeued with a fresh retry budget (atomically clearing `poisoned_at`, `attempts`, `next_attempt_at`, and `last_error`), and the relay exposes pending/due/poison counts plus oldest-pending lag.
- `ItemIngestService` keeps per-item `PROPAGATION_REQUIRES_NEW`; the item and outbox row commit atomically (`saveAndFlush` on both). Duplicates are skipped by precheck and then by the `uq_items_source_guid` constraint. A `23505` is benign only when the constraint context names `uq_items_source_guid`; an outbox `uq_outbox_topic_event_key` violation is a real failure, not a benign duplicate. Skipped/poison items produce no outbox row. When `feed.outbox.enabled=false`, neither relay nor emission is active.
- Only newly ingested items emit outbox events. Historical `items` rows are **not backfilled**; see `DECISIONS.md`.
- The producer `delivery.timeout.ms` is derived from `feed.outbox.send-timeout-ms`, so the Kafka future's own deadline matches the relay's bounded send wait.

## Test coverage and scheduler cleanup
- `FeedPollerServiceTest` directly verifies (1) a clean `200` with no validator headers clears stale `ETag`/`Last-Modified` and keeps them cleared on the next poll, and (2) the `robotsDisallowed()` path records `ROBOTS`, increments consecutive failures, leaves validators untouched, and never ingests. This complements `RobotsTxtParserTest` and `PerItemIngestIsolationTest`.
- `SchedulerInvokesPollerTest` now uses a `notModified` stub instead of repeated HTTP 500 failures, disables the outbox relay, and marks its context `@DirtiesContext(AFTER_CLASS)` so its background scheduler cannot leak WARN lines into later tests using the cached context.
- `KafkaOutboxSeamTest` now sets the canonical `feed.scheduling-enabled=false` key, enables loopback only for its local stub server, asserts retry metadata during the broker outage, and verifies a repeated relay publishes no duplicate records. It has zero skipped tests and zero mocks/shortcuts.
- `OutboxRelayServiceTest` covers transient retry/backoff, permanent poison quarantine with stored-topic publishing, retry exhaustion, requeue, and observability hooks.
- Post-merge additions cover two-worker `FOR UPDATE SKIP LOCKED` claiming, real-Kafka poison quarantine/recovery, outbox unique-constraint handling, per-hop robots checks, and static stub-server teardown.

## M3 completion / audit summary
- **V9 migration decision:** `V7` was renamed to `V9` because `main` had already applied `V8`; with `validate-on-migrate: true` and `out-of-order: false`, a resolved-but-unapplied older version would fail Flyway validation. V1–V8 → V9 is the only supported sequence; no re-baseline, checksum repair, or out-of-order flag.
- **No historical backfill:** V9 is pure DDL. Only newly ingested items emit events; pre-V9 rows and rows ingested with the outbox disabled never appear on the topic. Any replay must be an explicit, separately reviewed operation reusing the deterministic `sourceId:guid` key.
- **Atomic item + outbox emission:** each item and its outbox row are written in one `REQUIRES_NEW` transaction with `saveAndFlush` on both, so neither can exist without the other. Delivery is at-least-once; the unique `(topic, event_key)` constraint and deterministic key make downstream dedupe safe.
- **Merged baseline:** `84` tests run / `84` passed (`0` failures, `0` errors, `0` skipped) and `0` SpotBugs findings with `failOnError=true`. Post-merge hardening adds further tests; the final gate counts are recorded below and in the verification log.

## Verification
- Canonical command: `mvn clean verify` (used locally and in CI); `clean` removes any stale `target/classes/db/migration/V7__outbox_events.sql` before Flyway resolves migrations.
- Result: `BUILD SUCCESS`
- Tests: `95` run / `95` passed (`0` failures, `0` errors, `0` skipped).
- SpotBugs: `BugInstance size is 0`, `Error size is 0` (`failOnError=true`).
- Full log: `/tmp/opencode/post-m3-relay-tests.log`.
- No dead-container, NPE, context-startup, or Flyway errors. The 27 Kafka connection warnings match the prior full gate; WARN/ERROR lines that do appear are intentional negative-path assertions (HTTP 500/404/503, bad/oversized bodies, SSRF blocks, robots fail-closed, duplicate-key/NUL/check-constraint violations, parse failures, paused-Kafka outage/retry, and quarantined poison events).

## Environment notes
- Docker must be running for Testcontainers-backed tests (PostgreSQL 16.16 and Kafka 7.8.0).
- Local stub-server tests set `feed.ssrf-allow-loopback=true`; production default is `false`.
- A stale `target/classes/db/migration/V7__outbox_events.sql` could survive an incremental build after the rename. The canonical gate is `mvn clean verify` (CI runs the same command), which deletes `target/` first; do not publish an incremental `mvn verify` as the gate.
- Failsafe was removed from `pom.xml`: there are no `*IT` classes, so all tests (including Testcontainers-backed ones) run under Surefire during `test`; `verify` adds SpotBugs.

## Merge readiness
M3 is merged on `main`. The full gate is green, the migration ordering hazard is resolved, outbox failure behavior is covered, and the docs record the no-backfill decision.

## M4 Phase 1 — Observability & Outbox Housekeeping

- Branch: `feat/m4-observability` (base `main @ 7343057`).
- Added `V10__outbox_retention_and_metadata.sql`: partial cleanup index on `published_at`, plus `replayed_count` and `schema_version` columns.
- Added `OutboxCleanupService` with typed `feed.outbox.cleanup.*` properties, batched deletion of published rows only, and scheduling gated by `feed.scheduling.enabled` and `feed.outbox.cleanup.enabled`.
- Added `FeedlineMetrics` with low-cardinality Micrometer gauges/counters/timers for outbox, ingest, and fetch outcomes.
- Updated `OutboxEvent.requeueForRetry` to increment `replayedCount`.
- Tests: `FeedlineMetricsTest`, `OutboxCleanupServiceTest`, `OutboxCleanupIntegrationTest`.
- Verification: `mvn clean verify` — 102 tests, 0 failures/errors/skips; SpotBugs 0 bugs.

## M4 Phase 2 — Operator Admin API & Security Boundary

- Branch: `feat/m4-admin-api` (base `feat/m4-observability`).
- Added `feed.admin.enabled`, `feed.admin.api-key` (env `FEED_ADMIN_API_KEY`), `feed.admin.header-name` (default `X-Admin-Token`).
- Added `AdminTokenFilter` (constant-time comparison, `401`/`403` RFC 7807).
- Added `AdminOutboxController` + `AdminOutboxService`: list poisoned (deterministic order), single replay, batch replay.
- DTOs: `PoisonedOutboxEventDto`, `PoisonedOutboxPage`, `PoisonReplayResponse`.
- Tests: `AdminOutboxSeamTest`.
- Verification: `mvn clean verify` green, SpotBugs 0.

## M4 Phase 3 — Multi-Instance Source Leasing

- Branch: `feat/m4-poller-leases` (base `main` @ `116acb6`).
- Added `V11__source_poller_leases.sql`: nullable `lease_owner`/`lease_until` columns and partial claim index.
- Added typed `feed.poller.lease-duration` (default `PT2M`) and `feed.poller.worker-id` (default hostname + UUID) settings.
- `FeedSourceRepository` claims due sources with `FOR UPDATE SKIP LOCKED` and releases leases.
- `FeedPollerService` claims/releases leases, records `feedline.poller.leases.claimed.total` / `skipped.total`, and recovers expired leases.
- Added `TwoPollerConcurrencyIntegrationTest`.

## M4 Phase 4 — Robots Cache & Wildcard Matching, Normalizer Sanitization & Caps, Query Cursor Hardening

- Branch: `feat/m4-parser-query-polish` (base `main` @ `009c62a`).
- Bounded robots cache (`feed.robots.cache-max-entries`, `feed.robots.cache-ttl`) with LRU eviction and TTL expiry.
- Bot-specific vs wildcard user-agent group selection; safe `*`/`$` path matching.
- `FeedNormalizer` HTML stripping/entity decoding, configurable field caps, Atom/RSS content fallbacks, deterministic synthetic GUIDs with index support.
- Query cursor pagination keeps filters bound; malformed cursor/size handling unchanged (RFC 7807 400).
- Verification: `mvn clean verify` green, SpotBugs 0.
