# REPORT-AUDIT-COMPLETION — audit completion

Branch: `audit-completion`
Base: `df8457a` (main)

## Step 0 — precondition
- `grep -rn "feedline-admin-dev-token" src/` returned nothing (exit 1).
- `ops.js` contains `safeHttpUrl`.

## Part 1 — outbox invariant (OutboxInvariantTest)
Permanent Testcontainers test (real Postgres + real Kafka) covering faults
(a) DB failure mid-ingest, (b) broker down at relay time, (c) relay killed
between Kafka ack and mark-published. Asserts the set of items in the DB equals
the set of distinct eventKeys consumed, and consumed count >= distinct count.

Deliberate-break check: moved `event.markPublished(...)` before
`kafka.send(...).get(...)` in `OutboxRelayService.publishPending()`. Test result:
`Tests run: 1, Failures: 1`. Reverted; test green.

## Part 2 — route enumeration (AdminRouteEnumerationTest)
Every `/api/v1/admin/**` route x (no token, wrong token, burned string
`feedline-admin-dev-token`) -> 401. Path variants (trailing slash, double slash,
`;param`, encoded characters) never return an unauthenticated 200. With a key
configured, the right token succeeds on each route.

## Part 3 — pagination boundaries (ItemQueryPaginationIntegrationTest)
Shared-fetched_at batches larger than page size walked by cursor to exhaustion:
every row exactly once, no skips/repeats; hasMore/total correct. Cursor at exact
batch boundary and single-item final page covered.

## Part 4 — gzip bound
`FeedFetcher.readBodyWithLimit` now decompresses gzip/deflate on the fly and
applies `maxBodyBytes` to the decompressed stream. `FeedFetcherGzipTest` proves a
gzip bomb (small compressed, over-cap decompressed) is rejected with HTTP 413,
no OOM.

## Part 5 — V7 note
Added `src/main/resources/db/migration/README.md` explaining the skipped V7.

## Part 6 — MarketWatch removal
Removed MarketWatch from `application.yml` (9 sources remain). Created
`docs/rejected-sources.md` recording MarketWatch, CBC, and the 13 robots-blocked
removals by reference.

## Verification of record
Command: `mvn clean verify` (offline, clean tree).

Full surefire line:
`Tests run: 172, Failures: 0, Errors: 0, Skipped: 0`

SpotBugs verdict:
`BugInstance size is 0`

BUILD SUCCESS.
