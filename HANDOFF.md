# Feedline — Handoff

## Current branch
- Active branch: `fix/service-remediation` (created from `main` at `edee9ba`).
- Work is uncommitted; no commit/push was requested.
- `feat/m3-kafka` (transactional outbox + Kafka relay seam, `a06061d`) is parked and intentionally not merged.
  - It owns migration `V7__outbox_events.sql`; this remediation uses `V8__keyset_index_and_optimistic_locks.sql` to avoid a version collision if M3 is merged later.

## What changed
### Step 0 — test/log hygiene
- Added `feed.scheduling-enabled=false` to Spring tests that do not need the scheduler.
- Reworked `SchedulerInvokesPollerTest` with stable `@Primary` stub beans so early scheduler cycles cannot see unstubbed mocks (`id=null`, null `FeedFetchResult` NPE).
- Strengthened fetch/robots/query tests; added Atom and robots fixtures.

### Step 1 — data-fetch correctness
- `FeedFetcher.fetchWithRetry` now wraps every response body in try-with-resources and drains bounded bytes on 304/4xx/5xx/error paths.
- `ItemIngestService.ingest` uses a `TransactionTemplate` with `REQUIRES_NEW` per item. Duplicate races are classified by typed SQL causes/SQLState `23505` outside the inner transaction, avoiding `UnexpectedRollbackException` and poisoned transactions.
- `FeedPollerService` now clears stored validators on a clean 200 even when the response has no ETag/Last-Modified.
- Added `UrlSafety`: http/https only; blocks loopback (unless explicit test override), RFC1918, CGNAT, link-local/metadata, unspecified, multicast, IPv4-mapped IPv6, and unique-local IPv6 on both initial URLs and every redirect hop.

### Step 2 — operational hardening
- Migration `V8` adds `(fetched_at DESC, id DESC)` keyset index, skip-retention index, and optimistic-lock columns.
- `@Version` added to `FeedSource` and `IngestSkip`. **When saving a detached `FeedSource`, reassign from `save(...)`**; otherwise a later save in the same method fails with `StaleObjectStateException`.
- Readiness decoupled from poll heartbeat when `feed.scheduling-enabled=false`; `SourceHealthSeamTest` now asserts readiness UP without a poll.
- Skip recording is fail-safe (a skip-store failure no longer discards good items).
- `IngestSkipRecorder.prune` applies `feed.skip-retention` (default `30d`).
- `SourceRegistryInitializer` now syncs name/enabled metadata for existing sources and logs failures.

### Step 3 — build hygiene
- CI actions are SHA-pinned (`actions/checkout@11bd719...`, `actions/setup-java@3a4f6e1...`).
- Spring Boot remains `3.3.5`; Java `21`; springdoc `2.6.0`.
- Failsafe is BOM-managed and already bound by the Spring Boot parent; no duplicate execution was added.
- SpotBugs remains enforced with `failOnError=true`; one justified `EI_EXPOSE_REP2` exclusion added for `IngestSkipRecorder`'s injected repository.

## Verification
- Final command: `mvn clean verify`
- Result: `BUILD SUCCESS`
- Tests: `77 run, 0 failures, 0 errors, 0 skipped`
- SpotBugs: `BugInstance size is 0`, `Error size is 0`
- Full log: `/tmp/opencode/full-verify-final.log` (regenerate if the tmp directory is cleaned).

## Environment notes
- Docker must be running for Testcontainers-backed tests.
- Local stub-server tests set `feed.ssrf-allow-loopback=true`; production default is `false`.
- Never merge `feat/m3-kafka` without reconciling its V7 outbox migration and re-running `mvn clean verify`.
