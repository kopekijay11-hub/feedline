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
