# Feedline — Handoff

## Current branch
- Active branch: `feat/m3-kafka`.
- Verified HEAD: `b233547` (`Merge branch 'main' into feat/m3-kafka`); the M3 hardening below is uncommitted in the working tree because no commit was requested.
- Merge target: `main`. `main` at the merge point (`26956d0`) owns `V8__keyset_index_and_optimistic_locks.sql`; this branch owns `V9__outbox_events.sql`.

## M3 hardening
- Renamed the outbox migration from `V7` to `V9`. It now runs after `V8` on databases that already applied `V8`, which is required with `validate-on-migrate: true` and the default `out-of-order: false`.
- `V9` creates `outbox_events` with retry metadata (`attempts`, `next_attempt_at`, `last_error`, `poisoned_at`) and a unique `(topic, event_key)` constraint.
- `OutboxRelayService` publishes the stored event topic (not just the configured default), locks rows with `FOR UPDATE SKIP LOCKED`, records bounded exponential backoff on transient failures, and quarantines permanent failures or retry exhaustion as poison events. It stops the batch on the first transient failure so one outage cannot stall all 50 rows behind 5-second timeouts, but it continues past poison rows.
- There is no persisted "in-flight" state. A crash rolls back the relay transaction, releases row locks, and leaves the row publishable after its backoff; another instance can pick it up. Delivery is at-least-once, with an idempotent producer (`acks=all`, `enable.idempotence=true`, retries) and deterministic Kafka key `sourceId:guid` for downstream dedupe.
- `ItemIngestService` keeps per-item `PROPAGATION_REQUIRES_NEW`; the item and outbox row commit atomically (`saveAndFlush` on both). Duplicates are skipped by precheck and then by the `uq_items_source_guid` constraint, with SQLState `23505` classified outside the inner transaction. Skipped/poison items produce no outbox row. When `feed.outbox.enabled=false`, neither relay nor emission is active.
- Only newly ingested items emit outbox events. Historical `items` rows are **not backfilled**; see `DECISIONS.md`.

## Test coverage and scheduler cleanup
- `FeedPollerServiceTest` directly verifies (1) a clean `200` with no validator headers clears stale `ETag`/`Last-Modified` and keeps them cleared on the next poll, and (2) the `robotsDisallowed()` path records `ROBOTS`, increments consecutive failures, leaves validators untouched, and never ingests. This complements `RobotsTxtParserTest` and `PerItemIngestIsolationTest`.
- `SchedulerInvokesPollerTest` now uses a `notModified` stub instead of repeated HTTP 500 failures, disables the outbox relay, and marks its context `@DirtiesContext(AFTER_CLASS)` so its background scheduler cannot leak WARN lines into later tests using the cached context.
- `KafkaOutboxSeamTest` now sets the canonical `feed.scheduling-enabled=false` key, enables loopback only for its local stub server, asserts retry metadata during the broker outage, and verifies a repeated relay publishes no duplicate records. It has zero skipped tests and zero mocks/shortcuts.
- `OutboxRelayServiceTest` covers transient retry/backoff, permanent poison quarantine with stored-topic publishing, and retry exhaustion.

## Verification
- Final command: `mvn clean verify`
- Result: `BUILD SUCCESS`
- Tests: `84 run, 0 failures, 0 errors, 0 skipped`
- `KafkaOutboxSeamTest`: `2 run, 0 failures, 0 errors, 0 skipped`; no `@Disabled`, assumptions, `@MockBean`, or Mockito shortcuts.
- SpotBugs: `BugInstance size is 0`, `Error size is 0` (`failOnError=true`).
- Full log: `/tmp/opencode/full-verify-m3.log`.
- No dead-container, NPE, context-startup, or Flyway errors. WARN/ERROR lines that do appear are intentional negative-path assertions (HTTP 500/404/503, bad/oversized bodies, SSRF blocks, robots fail-closed, duplicate-key/NUL/check-constraint violations, parse failures, and the paused-Kafka outage/retry path).

## Environment notes
- Docker must be running for Testcontainers-backed tests (PostgreSQL 16.16 and Kafka 7.8.0).
- Local stub-server tests set `feed.ssrf-allow-loopback=true`; production default is `false`.
- A stale `target/classes/db/migration/V7__outbox_events.sql` can survive an incremental build after the rename; run `mvn clean` (or `mvn clean verify`) so only `V9` is on the classpath.

## Merge readiness
`feat/m3-kafka` is ready to merge into `main`: the full gate is green, the migration ordering hazard is resolved, outbox failure behavior is covered, and the docs record the no-backfill decision.
