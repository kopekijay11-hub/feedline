# REPORT-M2B-HARDENING

Branch: `m2b-hardening` from `origin/main` / `cb09dc8`, no merges.
Scope: Part A per-item ingest isolation, Part B actuator/source-health separation, Part C informational content freshness, bounded live check, final verify.
HEAD before this report: `344f88e1c3cf90a354db86c4b2a73813a4759c9e`.

## Environment

- Java: `openjdk version "21.0.13.0.3" 2026-10-24`
- Maven: `Apache Maven 3.9.12`
- Spring Boot: `3.3.5`
- Docker: PostgreSQL `16.16-alpine` via `docker-compose.yml`
- Working tree before report: `git status --short` empty

## Commits on branch

```text
344f88e feat(c): expose informational content freshness
f57d59a feat(b): split actuator health and make source health informational
ff86341 feat(a): isolate item ingest in poller and record skips
d3405c1 feat(a): add per-item ingest isolation schema and services
```

Diff from base: 22 files changed, 831 insertions(+), 45 deletions(-).

## Step A — Per-item ingest isolation

- `V6__ingest_isolation_and_freshness.sql` adds `ingest_skips`, `ck_items_title_max_length`, and `feed_sources.last_new_item_at`.
- `ItemIngestService.ingest(...)` is `@Transactional(propagation = REQUIRES_NEW)`; one item's failure cannot roll back a sibling item.
- `IngestSkipRecorder.record(...)` is also `REQUIRES_NEW`, so skip bookkeeping survives the failed item transaction.
- Failure classes: `TITLE_TOO_LONG`, `NUL_BYTE`, `DUPLICATE`, `DATA_INTEGRITY`, `VALIDATION`, `GUID_ERROR`, `INGEST_ERROR`.
- `FeedPollerService` calls the ingest service once per `SyndEntry`, records skips, and advances `etag`/`last_modified` only when `skipped == 0`.
- `lastNewItemAt` advances only when `saved > 0`; a duplicate-only poll leaves it unchanged.
- `PerItemIngestIsolationTest`: 4 tests, all green.

## Step B — Actuator and source-health separation

- `/actuator/health/liveness` includes only `livenessState`.
- `/actuator/health/readiness` includes `readinessState,db,pollCycleHeartbeat`; `sources` is excluded from all actuator groups.
- `SourcesHealthIndicator` is disabled with `management.health.sources.enabled: false` and `@ConditionalOnEnabledHealthIndicator("sources")`.
- `PollCycleHeartbeatIndicator` supplies the readiness heartbeat and omits the null detail instead of calling `withDetail` with null.
- `management.endpoint.health.show-details: never`; `show-components` was not enabled.
- `/api/v1/sources/health` now reports `sourcesStatus` (`UP`/`DEGRADED`) and skip counts, but its HTTP status follows readiness, not source health.
- `SourceHealthSeamTest`: 12 tests, all green, including group membership, contributor absence, degraded-sources HTTP 200, and readiness HTTP 503 when scheduling is disabled.

## Step C — Informational content freshness

- `FeedProperties.contentFreshnessThreshold` defaults to `7d`; `application.yml` declares `feed.content-freshness-threshold: 7d`.
- `SourceHealthService.contentFreshness(...)` returns `UNKNOWN`, `CURRENT`, or `STALE` using `lastNewItemAt` and the configured threshold.
- `SourceHealthDetail` exposes `lastNewItemAt` and `contentFreshness`; both are informational and do not change source state or readiness.
- The `CURRENT`/`STALE` seam test uses `feed.content-freshness-threshold=60s`; the live check below used a 5-second threshold to stay bounded.

## Step D — Bounded live check

Fresh out-of-tree compose volume `feedline_feedline_hardening_live_data` was used; `docker compose down` was later run without `-v`. Before app start:

```text
 flyway_history
 ----------------
(1 row)

```

A local stub served exactly one configured source with two RSS items: one valid item and one with a 1001-character title. Packaged `target/feedline-0.1.0-SNAPSHOT.jar` ran on port 18080 with `feed.poll-delay=15000`, `feed.content-freshness-threshold=5s`, and no external feeds. Two initial launch attempts used invalid temporary override values (`15s` for `fixedDelayString`, then out-of-range list slots) and were corrected in `/tmp` only; no repository files changed.

Actuator split (verbatim):

```text
--- liveness ---
{"status":"UP"}
HTTP 200
--- readiness ---
{"status":"UP"}
HTTP 200
--- aggregate-health ---
{"status":"UP","groups":["liveness","readiness"]}
HTTP 200
```

Source health and stored items (verbatim):

```text
--- sources-health ---
{"status":"UP","sourcesStatus":"UP","lastCompletedPollCycle":"2026-11-19T22:16:01.314386501Z","heartbeatStale":false,"pollIntervalSeconds":15,"sources":[{"id":1,"name":"Live Stub Source","state":"UP","lastAttemptAt":"2026-11-19T22:16:00.275078Z","lastSuccessAt":"2026-11-19T22:16:01.203085Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":10,"lastNewItemAt":"2026-11-19T22:16:01.203085Z","contentFreshness":"STALE","pollIntervalSeconds":15,"skippedItemCount":1,"skippedOccurrences":1}]}
HTTP 200
--- source-detail ---
{"id":1,"name":"Live Stub Source","state":"UP","lastAttemptAt":"2026-11-19T22:16:00.275078Z","lastSuccessAt":"2026-11-19T22:16:01.203085Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":10,"lastNewItemAt":"2026-11-19T22:16:01.203085Z","contentFreshness":"STALE","pollIntervalSeconds":15,"skippedItemCount":1,"skippedOccurrences":1}
HTTP 200
--- items ---
{"items":[{"id":1,"sourceId":1,"guid":"live-hardening-valid-1","link":"http://127.0.0.1:18099/valid","title":"Live hardening valid item","summary":"Valid item survives an adjacent bad item.","publishedAt":"2026-11-19T12:00:00Z","fetchedAt":"2026-11-19T22:16:01.203085Z"}],"nextCursor":null,"total":1,"hasMore":false}
HTTP 200
```

The valid item was stored even though the adjacent overlong item was skipped. The 5-second threshold had already elapsed at capture, so the live `contentFreshness` was `STALE` while `state` and HTTP status stayed `UP`; the `CURRENT` branch is covered by `SourceHealthSeamTest`.

After three total polls (verbatim source detail):

```json
{"id":1,"name":"Live Stub Source","state":"UP","lastAttemptAt":"2026-11-19T22:16:31.376795Z","lastSuccessAt":"2026-11-19T22:16:31.399271Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":4,"lastNewItemAt":"2026-11-19T22:16:01.203085Z","contentFreshness":"STALE","pollIntervalSeconds":15,"skippedItemCount":1,"skippedOccurrences":3}
```

Database proof (trailing spaces trimmed):

```text
 items | skip_rows | failure_class  | occurrences |       stored_title        |      last_new_item_at
-------+-----------+----------------+-------------+---------------------------+----------------------------
     1 |         1 | TITLE_TOO_LONG |           3 | Live hardening valid item | 2026-11-19 22:16:01.203085
(1 row)
```

`last_new_item_at` remained at the first successful stored-item timestamp while `occurrences` rose to 3. Relevant app log lines (verbatim):

```text
2026-11-19T17:16:01.298-05:00  WARN 2255305 --- [feedline] [   scheduling-1] ca.feedline.service.FeedPollerService    : Skipped item live-hardening-overlong-1 from http://127.0.0.1:18099/feed: TITLE_TOO_LONG
2026-11-19T17:16:01.314-05:00  INFO 2255305 --- [feedline] [   scheduling-1] ca.feedline.service.FeedPollerService    : Polled http://127.0.0.1:18099/feed (source id 1) -> 1 items saved at 2026-11-19T22:16:01.203085235Z
2026-11-19T17:16:31.439-05:00  WARN 2255305 --- [feedline] [   scheduling-1] ca.feedline.service.FeedPollerService    : Skipped item live-hardening-overlong-1 from http://127.0.0.1:18099/feed: TITLE_TOO_LONG
2026-11-19T17:16:31.446-05:00  INFO 2255305 --- [feedline] [   scheduling-1] ca.feedline.service.FeedPollerService    : Polled http://127.0.0.1:18099/feed (source id 1) -> 0 items saved at 2026-11-19T22:16:31.399271315Z
```

Graceful `SIGTERM` produced JPA/Hikari shutdown logs; the stub process and compose container were stopped, ports 5432/18080/18099 were free afterward, and no processes were left running.

## Step E — Verify

`free -h` / `uptime` before final verify:

```text
               total        used        free      shared  buff/cache   available
Mem:            14Gi        12Gi       1.3Gi       510Mi       1.7Gi       2.3Gi
Swap:           15Gi        10Gi       5.5Gi
 17:08:41 up 6 days, 15:16,  1 user,  load average: 7.26, 8.49, 7.79
```

`free -h` / `uptime` during live capture:

```text
               total        used        free      shared  buff/cache   available
Mem:            14Gi        13Gi       471Mi       639Mi       2.0Gi       1.6Gi
Swap:           15Gi        10Gi       5.6Gi
 17:16:49 up 6 days, 15:25,  1 user,  load average: 6.30, 7.58, 7.77
```

`mvn clean verify`: **BUILD SUCCESS**, Total time `02:40 min`.

Tests: **52 run, Failures: 0, Errors: 0, Skipped: 0**.

Per class:

| Test class | Tests |
| --- | ---: |
| `ItemsApiSeamTest` | 8 |
| `SourceHealthSeamTest` | 12 |
| `FeedPollerFixtureIntegrationTest` | 6 |
| `FeedNormalizerTest` | 6 |
| `FeedFetcherStubServerTest` | 6 |
| `PerItemIngestIsolationTest` | 4 |
| `FeedPollerIntegrationTest` | 4 |
| `FeedPollerDedupeIntegrationTest` | 2 |
| `FeedFetcherTest` | 2 |
| `SchedulerInvokesPollerTest` | 1 |
| `ApplicationContextSmokeTest` | 1 |

SpotBugs verdict (verbatim):

```text
BugInstance size is 0
Error size is 0
No errors/warnings found
```

No merges, no cleanup deletions, no `git checkout` discards, no removal of untracked files, and no historical report edits were made. One heavy process was used at a time.
