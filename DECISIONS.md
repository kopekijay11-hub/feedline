# DECISIONS

## M1

**How does dedupe work?**
(source_id, guid). The insert is insert-if-absent, so polling the same feed a second time inserts zero new rows. The existing rows are left as they are.

**Why pin the database image to an exact tag?**
postgres:16 is a moving tag, so the same compose file can pull a different minor version next month. An exact tag like 16.16-alpine means the database you verified against is the one every future build runs, so a failure points to your change and not to a silent upgrade.

**Why Flyway instead of Hibernate auto-create?**
Flyway applies versioned SQL files in order, keeps them in git, and checksums them, so schema changes are reviewable and repeatable. Hibernate auto-create guesses the schema from the entity classes, leaves no change history, and can't be trusted with real data. Set Hibernate to validate only.

**The lesson of the three walls**
The lesson of the three walls: all three failures presented as something they weren't, and each was diagnosed the moment someone stopped trusting the label and asked what the error actually meant—so the discipline isn't "fix the error," it's "prove the error is what it claims to be before fixing it."

## M2a

See REPORT-M2A.md for the grading record behind these decisions.

**Why are ETag and Last-Modified only saved alongside a successful ingest?**
ETag and Last-Modified are a promise to the server: "I already have everything up to this version." They are conditional-GET bookmarks — the next poll sends them, and a 304 Not Modified means "nothing new since." If a failed poll (network error mid-body, parse exception, DB write failure after fetch, whatever) advanced those tokens, we'd be telling the server we've consumed content we never actually persisted. Next poll: 304, empty body, and those items are gone — not delayed, gone, because the server has no reason to resend them. It's a silent, permanent data loss that only surfaces as a hole in the timeline weeks later. Saving them in the same transaction as the ingest makes the bookmark and the data atomic: either the items are in Postgres and the token is stored, or neither is. A clean `200` with no ETag/Last-Modified headers deliberately clears previously stored tokens, because the server has stopped asserting a version and keeping stale tokens would cause a false `304` that hides items. Rollback on failure keeps the bookmark honest — we'll re-fetch the same range next time, get the same items, and either commit both or repeat. Idempotency on (source_id, guid) means the re-fetch is safe.

**Why is the derived GUID deterministic?**
Same reason, one layer up. The GUID is the dedupe key. If it were random per poll, every re-fetch — and we re-fetch on any failed transaction, on token mismatch, on a 304-that-wasn't, on a restart — would produce fresh GUIDs for items we already have, and the (source_id, guid) constraint would happily insert duplicates. The feed would grow a new copy of its whole recent history on every poll. Deterministic derivation from stable content (title + link + published, canonically ordered, hashed) means the same item always hashes to the same key: insert-if-absent becomes a true no-op on re-poll, and the DB constraint becomes a real backstop instead of a formality. It also makes the GUID reproducible — I can recompute it from the raw item and audit any dedupe decision. Random GUIDs make the constraint load-bearing in the wrong way: it stops the duplicates only if the randomness happens to collide, which it won't.

**Which dedupe is authoritative — the precheck or the constraint?**
The DB constraint is the authority. The precheck is an optimization and a fast path. The constraint is the only thing that is actually true under concurrency: two ingest workers, or one worker and a backfill, can both pass the precheck for the same GUID and both attempt the insert — only the unique index arbitrates that race. The precheck exists so we don't pay an exception on the common case and so the code reads intentionally ("we expected this to be absent"). The constraint exists so the uncommon case — race, retry, replay, a bug in the precheck — cannot corrupt the data. If I had to keep one, I'd keep the constraint and delete the precheck; the reverse is unsafe. Keeping both is the correct belt-and-suspenders: precheck for throughput and clarity, constraint for correctness. And critically, the ingest path must handle the constraint violation as a normal "already present," not as a fatal error — otherwise the backstop turns into an outage.

**The lesson of unique = true**
The lesson is that an annotation on the entity is a declaration of intent, not a schema. Hibernate will generate the constraint if you let it own DDL, but we own the schema through migrations — V1 was hand-written, and hand-written DDL doesn't read your JPA annotations. So the entity said one thing and the database said another, and nothing failed, because nothing in the system ever checked that the two agreed. The test that forced the issue was the first thing that actually exercised the invariant end-to-end. Two lessons, really: (a) if the entity declares a constraint, a migration must create it, and the only reliable proof is a test that violates it and expects failure — a "constraint exists" assertion, not a hope; (b) the general form — when two sources of truth (annotation and DDL, or config and code) can disagree silently, add a test that makes them disagree loudly. The bug wasn't the missing constraint so much as the absence of anything that would have caught it. That's why V3 both adds the constraint and (per the M2a repair) the suite now has a test that would fail if it went missing again.

## Service remediation

**Why duplicate-key races are handled outside the transaction**
A unique violation during `saveAndFlush` marks the JPA transaction rollback-only. Catching it inside the same `@Transactional` method and returning normally still makes commit throw `UnexpectedRollbackException`. `ItemIngestService` therefore runs each item in a `REQUIRES_NEW` `TransactionTemplate`; the duplicate exception escapes the inner transaction (which rolls back cleanly), and the non-transactional caller classifies it by SQLState `23505` and returns "not stored". The next item gets a fresh transaction.

**Why the SSRF guard is fail-closed**
Checking only the URL scheme is not enough: `http://127.0.0.1`, `http://169.254.169.254`, and IPv4-mapped IPv6 literals all point at internal targets. `UrlSafety` validates http/https plus every resolved address, rejects if any address is private/special, and applies the same check to every redirect hop. Loopback is allowed only for local stub servers through the explicit `feed.ssrf-allow-loopback` test seam.

**Why readiness ignores the poll heartbeat when scheduling is disabled**
A heartbeat proves "the scheduler ran", not "the application is ready". Tests and worker-style deployments disable `@Scheduled` and drive `pollOnce()` directly, so requiring a heartbeat made readiness permanently DOWN. The canonical flag is `feed.scheduling-enabled` (hyphen), not `feed.scheduling.enabled`; when it is false, `SourceHealthService.heartbeatStale()` returns false and readiness depends on liveness/db state instead. `KafkaOutboxSeamTest` now uses the canonical key so the scheduler cannot silently stay alive in a cached context.

**Why optimistic locking requires saving the returned entity**
`@Version` on `FeedSource` turns a second save of the same stale detached instance into `StaleObjectStateException`. `FeedPollerService` now reassigns `source = feedSources.save(source)` when it needs to keep working with the entity; early-return branches still save without reassignment.

## M3 — Kafka outbox

**Why the outbox migration is V9, not V7**
`main` applied `V8__keyset_index_and_optimistic_locks.sql` while the outbox work sat on `feat/m3-kafka` with `V7__outbox_events.sql`. Merging that V7 into a database already at V8 would fail Flyway validation: `validate-on-migrate: true` plus the default `out-of-order: false` rejects a resolved-but-unapplied older version. Renaming the outbox table migration to `V9` makes the sequence V1–V8 → V9 on every target database, with no re-baseline, no out-of-order flag, and no checksum repair. V8 keeps its keyset/skip-retention/optimistic-lock role; V9 only creates the outbox schema.

**Historical items are not backfilled**
Only newly ingested items emit outbox events. The V9 migration is pure DDL — it contains no `INSERT ... SELECT` from `items` — and there is no replay job. Items that already existed before V9, or that were ingested while `feed.outbox.enabled=false`, will never appear on `feedline.items.ingested`. This is deliberate: a silent automatic backfill would flood a live topic with an unbounded replay and would have no defined ordering or replay boundary. If a replay is needed, it must be an explicit, separately reviewed operation that writes outbox rows for a chosen `(source_id, guid)` range and reuses the same deterministic key. Duplicate/replayed deliveries are safe for downstream consumers that dedupe by key.

**How the relay is crash-safe and idempotent**
The item row and its outbox row are written in the same `REQUIRES_NEW` transaction in `ItemIngestService`, so there is no window where one exists without the other. `OutboxRelayService` selects publishable rows with `FOR UPDATE SKIP LOCKED`; there is no persisted in-flight state to get stuck. A crash rolls back the relay transaction, releases the row locks, and leaves the row pending for retry. The producer uses `acks=all` and `enable.idempotence=true`, and each event is published with the deterministic key `sourceId:guid` and a payload stored at ingest time. The system is therefore at-least-once: a crash between Kafka acknowledgement and the DB mark can still duplicate a record, so consumers must dedupe by key. The unique `(topic, event_key)` constraint prevents duplicate outbox rows from being created in the first place.

**Why transient and permanent publish failures are treated differently**
A transient failure (timeout, network/broker unavailability) increments `attempts`, records `last_error`, and schedules exponential backoff via `next_attempt_at`; the batch stops after the first transient failure so one outage cannot turn a 50-row batch into 250 seconds of blocked time. A permanent failure (serialization, invalid topic, oversized record, auth) or retry exhaustion sets `poisoned_at`, which quarantines the row without falsely marking it published. Poison rows are excluded from the publishable query instead of blocking every later event forever, and the relay continues past them.

**Why the relay uses the stored topic**
`OutboxEvent.topic` is captured when the event is created. The relay publishes to that stored value rather than the current `feed.outbox.topic` property, so changing configuration after events are queued cannot silently redirect old events to a new topic.

**Why the scheduler test no longer emits background warnings**
`SchedulerInvokesPollerTest` must leave scheduling on to prove the scheduler invokes the poller. Its stub now returns `304 Not Modified` instead of repeated HTTP 500 failures, `feed.outbox.enabled=false` prevents relay startup work, and `@DirtiesContext(AFTER_CLASS)` closes the context so its scheduler thread cannot keep logging into later tests that share the cached context.

## M4 Phase 1 — Observability & Outbox Housekeeping

**Why is the outbox retention migration V10?**
The existing sequence is V1–V6, V8, V9 (V7 was skipped during the M3 merge). The next sequential version is V10, so the retention/metadata migration is named `V10__outbox_retention_and_metadata.sql`. It adds a partial cleanup index on `published_at`, plus `replayed_count` and `schema_version` columns with safe `IF NOT EXISTS` guards. V9's partial claim index is left untouched and no historical backfill is performed.

**Micrometer cardinality rules**
Metrics use low-cardinality outcome tags only (`published`, `retry_scheduled`, `poisoned`, `replayed`, `inserted`, `duplicate`, `skipped`, `ok`, `not_modified`, `error`). Raw URLs, GUIDs, exception messages, and per-feed identifiers are never used as tags. Gauges are backed by repository count queries; timers record durations without tags.

## M4 Phase 2 — Operator Admin API & Security Boundary

**Why header auth instead of Spring Security**
`/api/v1/admin/**` is protected by a dedicated `OncePerRequestFilter` (`AdminTokenFilter`) scoped to that path prefix, avoiding `spring-boot-starter-security` and its default auto-configuration that could alter the public `/api/v1/items`, `/api/v1/sources/health`, and `/actuator/**` surfaces. The token is compared with `MessageDigest.isEqual` on UTF-8 bytes. Missing or non-matching tokens return `401 application/problem+json`; a blank or disabled configured token fails closed with `403`.

**Admin replay idempotency**
Single replay requires the row to exist and be currently poisoned; a missing row returns `404`, while a published or pending (non-poisoned) row returns `409 Conflict` rather than silently requeueing. Replay resets `poisoned_at`, `published_at`, `attempts`, `next_attempt_at`, and `last_error`, and increments `replayed_count` plus `feedline.outbox.events.total{outcome="replayed"}`.

## M4 Phase 3 — Multi-Instance Source Leasing

**Why V11 is the next migration**
The sequence is V1–V6, V8, V9, V10 (V7 was skipped during the M3 merge). V11 follows V10 and adds nullable `lease_owner`/`lease_until` columns plus a partial claim index. No historical rows are rewritten; unleased rows (NULL) are immediately claimable.

**Why `FOR UPDATE SKIP LOCKED`**
Multiple poller replicas select due, enabled, unleased (or expired-lease) rows in a short transaction with `FOR UPDATE SKIP LOCKED`. Locked and already-leased rows are skipped rather than blocking, so replicas make progress without deadlocking. Each claimed row has `lease_owner`/`lease_until` set immediately, bounding duplicate fetches to the lease window.

**Why leases are released after processing**
After a poll succeeds, returns 304, or fails, the lease is cleared in a follow-up update so healthy sources do not wait for lease expiry. An ungraceful crash leaves `lease_until` in place; once it expires another replica safely reclaims the source. This gives crash recovery without manual intervention.

**Why `last_attempt_at` drives due-ness**
The schema has no per-source `poll_interval_seconds`; due-ness reuses the existing `last_attempt_at` timestamp (NULL means never polled, hence due). Lease expiry is the recovery mechanism for stuck workers.

## M4 Phase 4 — Robots Cache & Wildcard Matching, Normalizer Sanitization & Caps, Query Cursor Hardening

**Bounded robots cache**
`RobotsTxtParser` now uses a bounded `LinkedHashMap` (access order) with configurable `feed.robots.cache-max-entries` (default 256) and `feed.robots.cache-ttl` (default `PT1H`), evicting the least-recently-used origin when full and re-evaluating after TTL expiry. Errors remain uncached.

**Bot-specific group selection**
Parsing now builds user-agent groups; a bot-specific group matching the configured user-agent prefix is selected when present, otherwise wildcard `*` groups are used. Bot and wildcard rules are not merged across unrelated groups.

**Safe path matching**
`*` wildcards and trailing `$` anchors are evaluated with escaped literal matching (no regex injection / catastrophic backtracking). Longest match wins, with `Allow` winning ties.

**Normalizer hardening**
`FeedNormalizer` strips HTML tags, decodes HTML/XML entities, and enforces configurable caps for title/summary/link/GUID. Atom `<content>`/`<summary>` and RSS `<description>`/`<content:encoded>` fallbacks are supported. Synthetic GUIDs incorporate link/title/published date and an optional entry index.

**Query cursor alignment**
Keyset pagination keeps cursor predicates alongside active filters; malformed cursors and out-of-range sizes continue to return RFC 7807 `400` responses.

## M5 Phase 1 — Outbox Event Schema, Headers & Deterministic eventKey

**Deterministic eventKey**
`ItemIngestService` derives `eventKey` as `sourceId:guid` at ingest time and stores it on both the Kafka message key and the `outbox_events.event_key` column. The same value survives retries and admin poison replay because `AdminOutboxService`/`OutboxRelayService` only update retry metadata (`attempts`, `next_attempt_at`, `last_error`, `poisoned_at`, `published_at`, `replayed_count`) and never rewrite `event_key`, `topic`, or `payload`. The unique `(topic, event_key)` constraint makes duplicate outbox rows impossible.

**Schema versioning and headers**
Each published record carries the stored `schemaVersion` (currently 1) plus `eventKey`, `eventType`, `occurredAt` (UTC ISO-8601 instant), and `sourceId` (when available) both as top-level JSON fields in `ItemIngestedEvent` and as Kafka record headers with the same names. Existing fields (`itemId`, `guid`, `title`, `link`, `publishedAt`, `ingestedAt`) remain for backward compatibility. Headers are UTF-8 string bytes.

**Consumer idempotency contract**
Downstream consumers must dedupe by the Kafka message key / `eventKey` (e.g. `INSERT ... ON CONFLICT (event_key) DO NOTHING` or an equivalent unique-key upsert). Because delivery is at-least-once (relay crash between Kafka ack and DB mark), duplicate deliveries and replayed poison events are expected and safe. Consumers should treat `schemaVersion` as the payload evolution marker and ignore unknown fields.

**Low cardinality**
Logging and metric tags remain low-cardinality and never include raw payloads, GUIDs, URLs, or tokens.

## M5 Phase 2 — Outbox Relay Batch/Partition Tuning & Poison Diagnostics

**Bounded relay configuration**
`feed.outbox.*` is now bound through `FeedProperties.Outbox` (`enabled`, `topic`, `poll-delay`, `batch-size`, `max-attempts`, `initial-backoff-ms`, `max-backoff-ms`, `send-timeout-ms`) in addition to the existing `@Value` call sites. Setters clamp batch size to `1..1000`, attempts to `1..100`, initial backoff to `1ms..600s`, max backoff to `1ms..1h` (never below the initial backoff), and send timeout to `1ms..60s`. `OutboxRelayService` applies the same clamps to its direct constructor so tests and programmatic callers cannot create an unbounded claim or a non-positive send wait. This keeps every relay batch bounded and predictable under burst load.

**Claim ordering and lock duration**
`findPublishable` still uses `FOR UPDATE SKIP LOCKED`, but now orders by `(next_attempt_at, id)`. That matches the existing partial index `idx_outbox_publishable (next_attempt_at, id)` and prevents an older but not-yet-due row from starving due retries behind newer rows. The batch limit is the configured, clamped batch size, so lock duration is bounded by one batch and the per-send timeout; the relay still stops at the first transient failure rather than holding the transaction open across an outage.

**Partition key and header stability**
The Kafka record key is the deterministic `eventKey` (`sourceId:guid`), and the same value is written to the `eventKey` header alongside `schemaVersion`, `eventType`, `occurredAt`, and `sourceId`. Retries and admin replays only mutate retry metadata; they never rewrite `topic`, `event_key`, or `payload`. Consequently a retried or replayed row hashes to the same partition and preserves per-key ordering. The real-Kafka test asserts `partition == murmur2(key) % partitions` on a three-partition topic.

**Poison diagnostics without secret leakage**
`V12__outbox_last_attempt_at.sql` adds a nullable `last_attempt_at` column, written by `OutboxEvent.recordFailure`. The admin poisoned-event DTO now exposes `attempts`, `replayedCount`, `poisonedAt`, `lastAttemptAt`, `nextAttemptAt`, and a sanitized `lastError`. `PoisonErrorSanitizer` removes stack-frame lines, redacts bearer/authorization credentials, key/value secrets (`api_key`, `password`, `token`, `secret`, ...), URL userinfo, and common token prefixes, collapses control characters, and caps the summary at 500 characters. Secrets and raw stack traces therefore cannot reach API clients.

**Replay semantics and metrics**
`replayBatch` clamps its limit to `1..500`, locks candidate rows with `FOR UPDATE SKIP LOCKED`, and reports rows that are no longer poisoned in `skippedIds`. Replay still resets `poisoned_at`, `published_at`, `attempts`, and `last_error`, increments `replayed_count`, and leaves `event_key`/`payload`/`schema_version` untouched. `feedline.outbox.events.total` keeps its fixed `outcome` tag set (`published`, `retry_scheduled`, `poisoned`, `replayed`); no exception text, URL, GUID, or token is used as a metric tag.

## M5 Phase 3 — Source Health & Backoff Alignment, Graceful Shutdown, Metrics Coverage

**Why source backoff is persisted instead of in-memory**
A poller replica can restart, lose leadership, or be replaced mid-cycle. If backoff lived only in a JVM map, a restart would immediately re-claim every failing source and hammer the upstream again. `V13__source_health_backoff.sql` therefore persists `next_poll_at` next to the source, and the claim queries require `next_poll_at IS NULL OR next_poll_at <= now`. Backoff is part of the durable source state, not process-local bookkeeping, so every replica observes the same deferral. The fallback configured-source loop applies the same check so a claim-path outage cannot turn into an upstream hammer.

**Why exponential backoff is bounded and honours Retry-After**
The base delay (`feed.poller.backoff-base`, default 30s) doubles per consecutive failure up to `feed.poller.max-backoff` (default 1h). An upstream `Retry-After` on a 429 or 503 is parsed in `FeedFetcher`, clamped to one hour, and used as a lower bound on the next poll. Both the exponential schedule and the server hint are bounded, so a hostile or misconfigured origin cannot push the poller into a multi-day sleep and a transient failure cannot retry forever with no ceiling. A clean 200/304 clears `consecutive_failures`, `next_poll_at`, `last_error`, and `last_error_at`; validators are still only advanced after successful ingest.

**Why backoff is a distinct health state**
`SourceHealthService` reports `UP`, `DEGRADED`, `BACKOFF`, `DISABLED`, or `UNKNOWN`. Generic failures stay `DEGRADED` (matching the existing health contract); 429/503 responses with an active `next_poll_at` are `BACKOFF`, because the operator needs to distinguish "the feed is broken" from "we are deliberately waiting". The detail view adds `nextPollAt`, `lastErrorAt`, and a sanitized `lastError`; `PoisonErrorSanitizer` is applied on the way out so credentials or tokens in an upstream message cannot leak through the public health API.

**Why leases are released on graceful shutdown**
An ungraceful crash intentionally leaves `lease_until` in place so another replica reclaims the source after expiry. A graceful exit is different: the worker knows it will not poll again, so waiting for the two-minute lease would strand every source it owned. `FeedPollerService` exposes a `@PreDestroy` hook that stops new claims, waits at most `feed.poller.shutdown-timeout` for an in-flight cycle, and bulk-clears `lease_owner`/`lease_until` for its worker id. The outbox relay and cleanup service flip a running flag so no new batches start; an in-flight batch still gets its bounded send window. `server.shutdown: graceful` plus `spring.lifecycle.timeout-per-shutdown-phase` and scheduler await-termination settings make the same bound explicit at the Spring level.

**Why the new metrics stay low cardinality**
Poll outcomes use a fixed four-value `outcome` tag (`success`, `not_modified`, `rate_limited`, `error`); unknown values bucket into `error` rather than creating a new series. Source health uses a fixed `state` tag (`healthy`, `degraded`, `backoff`, `disabled`, `unknown`) with one gauge per state and an `active` gauge. Shutdown uses a fixed `outcome` tag (`lease_released`, `lease_release_failed`, `relay_stopped`, `cleanup_stopped`, `completed`). No URL, exception text, GUID, token, or per-source identifier is ever used as a tag.
