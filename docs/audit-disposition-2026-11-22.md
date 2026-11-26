# Feedline — M3 Audit Disposition Table

Audit: REPORT-AUDIT-M3-2026-11-22.md (branch audit/m3-2026-11-22;
2026-11-22; 1 P0, 8 P1, 9 P2; verdict CONDITIONAL PASS).
Completion evidence: REPORT-AUDIT-COMPLETION.md; P0 evidence:
REPORT-P0-ADMIN-TOKEN.md. Residuals: REPORT-AUDIT-RESIDUALS.md.

Preamble — the P0: A-01, committed default admin token
feedline-admin-dev-token — FIXED (default removed, admin fails
closed with 401 when unset; AdminTokenDefaultTest /
AdminTokenConfiguredTest; merged).

## P1 findings (8)

| ID | Finding (audit wording) | Disposition | Evidence |
|---|---|---|---|
| A-02 | esc() does not neutralize javascript: scheme in href (ops.js) — a click on a crafted link executes script | FIXED | ops.js on main: safeHttpUrl() parses with URL and links only http:/https:; other URLs render as escaped text marked "(not a link)" |
| A-03 | No automated pagination boundary test (fetched_at, id cursor) | FIXED | REPORT-AUDIT-COMPLETION.md Part 3: ItemQueryPaginationIntegrationTest — shared-fetched_at batches walked by cursor to exhaustion, every row exactly once, hasMore/total correct |
| A-04 | Gzip decompression unbounded (CPU/memory) — the 5 MB cap applied post-decompression | FIXED | REPORT-AUDIT-COMPLETION.md Part 4: FeedFetcher.readBodyWithLimit bounds the decompressed stream; FeedFetcherGzipTest rejects a gzip bomb with HTTP 413 |
| A-05 | Outbox invariant test (Testcontainers + real Kafka) not run in the audit | FIXED | REPORT-AUDIT-COMPLETION.md Part 1: OutboxInvariantTest — DB failure mid-ingest, broker down, relay killed between ack and mark-published; deliberate-break check failed as designed, reverted, green |
| A-06 | Route enumeration probe not automated | FIXED | REPORT-AUDIT-COMPLETION.md Part 2: AdminRouteEnumerationTest — every /api/v1/admin/** route x (no token, wrong token, burned string) -> 401; path variants never return unauthenticated 200 |
| A-07 | Missing V7 migration unexplained | FIXED | src/main/resources/db/migration/README.md explains the skipped V7 |
| A-08 | Admin API exposes no token-configured check at startup | FIXED | By the A-01 fix: the default no longer exists (apiKey defaults to null, no yml fallback), so the guarded condition cannot occur; admin fails closed when unset. No separate startup warning was implemented; the disposition rests on the default's removal |
| §18 | docker-compose.yml publishes Postgres 5432:5432 and Kafka 9092:19092 to host (LAN exposure) | FIXED | loopback binding (127.0.0.1:5432:5432 Postgres, 127.0.0.1:9092:19092 Kafka), this job (REPORT-AUDIT-RESIDUALS.md Part 4) |

## P2 findings (9)

| ID | Finding (audit wording) | Disposition | Evidence |
|---|---|---|---|
| B-01 | No stable eventId for consumer dedupe (relies on eventKey only) | FIXED | README states the at-least-once contract: consumers must dedupe on the event key (the audit's offered documentation remedy) |
| B-02 | Poisoned rows retained indefinitely (no bound) | ACCEPTED/OPEN | Indefinite retention with manual admin replay/cleanup is the documented operating model (README, Outbox Controls) |
| B-03 | SSRF error messages may leak internal topology — UrlSafety messages not inspected in the audit | FIXED | topology-leaking messages sanitized to generic rejections naming no resolved IP/hostname; test asserts sanitized shape (REPORT-AUDIT-RESIDUALS.md Part 1) |
| B-04 | Readiness gate couples single source health to global DOWN | FIXED | Aggregate status derives solely from the actuator readiness group (readinessState, db, pollCycleHeartbeat); per-source state no longer drives it |
| B-05 | Dashboard auto-refresh may hammer endpoints (15 s) | ACCEPTED/OPEN | The audit itself recorded: "Acceptable; no action" |
| B-06 | No integration test for GUID scoping / unstable GUID behavior | FIXED | GuidScopingIntegrationTest — same GUID from two sources both stored; same source re-ingesting same GUID stores no duplicate; changed GUID between polls stored as new item (REPORT-AUDIT-RESIDUALS.md Part 2) |
| B-07 | FeedNormalizer synthetic GUID uses SHA-256 of link/title/published — collision resistance assumed | ACCEPTED/OPEN | unchanged; assumption documented in README ("Items without a feed-provided GUID receive a synthetic GUID: SHA-256 of link/title/published_at; collision resistance is assumed"), per the audit's accepted disposition |
| B-08 | Kafka max.in.flight.requests.per.connection=5 with idempotence — safe but not optimal for strict ordering | FIXED | README ordering paragraph states configured posture: idempotence on (enable.idempotence: true), acks: all, max.in.flight.requests.per.connection absent (framework default), ordering per-partition; events sharing key (sourceId:guid) keep order; no global ordering claimed (REPORT-AUDIT-RESIDUALS.md Part 3) |
| B-09 | ingest_skips prune runs every poll (60 s) — could be less frequent | ACCEPTED/OPEN | The audit itself recorded: "Acceptable; low overhead" |
