# REPORT-M2B-HEALTH

## Environment
- Java: openjdk 21.0.13.0.3
- Maven: 3.9.12
- Branch: m2b-health
- Base: origin/main (ba28792)

## Step 0 — Schema survey
a. `feed_items` (table `items`) ingestion-timestamp column: `fetched_at`, type `TIMESTAMP`, `NOT NULL` (src/main/java/ca/feedline/domain/Item.java: `@Column(name = "fetched_at", nullable = false)`; V1 migration `fetched_at TIMESTAMP NOT NULL`).
b. Text columns on `items`: `title` (VARCHAR(1000) NOT NULL), `summary` (TEXT, nullable), `link` (VARCHAR(1024) NOT NULL), `guid` (VARCHAR(512) NOT NULL) (Item.java; V1 migration).
c. Per-source fetch state before M2B: `etag` (TEXT, nullable) and `last_modified` (TEXT, nullable) only (V2 migration; FeedSource.java). No attempt/success/error/failure counters existed.

## Step 1 — Per-source fetch state
- Migration `V4__add_per_source_fetch_state.sql` adds `last_attempt_at TIMESTAMP`, `last_success_at TIMESTAMP`, `last_error_class VARCHAR(64)`, `consecutive_failures INTEGER NOT NULL DEFAULT 0`.
- FeedPollerService records attempt on every poll; success updates `last_success_at` and resets failures; failures set error class and increment count.

## Step 2 — Poll-cycle heartbeat
- `FeedPollerService.lastCompletedPollCycle` (volatile Instant), exposed via `getLastCompletedPollCycle()`; set in `pollOnce()` finally block. Null until first completed cycle.

## Step 3 — Health surface
- Actuator `HealthIndicator` bean named `sources`.
- Controller `GET /api/v1/sources/health` (aggregate, 503 when degraded/stale heartbeat) and `GET /api/v1/sources/health/{sourceId}` (404 problem+json for unknown id).

## Step 4 — Seam tests
- `SourceHealthSeamTest`: 5 real-context HTTP tests (degrade, never-polled, heartbeat, unknown source, canary). All passing.

## Step 5 — Bounded live check
Aggregate health payload (verbatim):

```json
{"status":"DOWN","lastCompletedPollCycle":"2026-11-19T07:28:42.497953994Z","heartbeatStale":false,"pollIntervalSeconds":60,"sources":[{"id":1,"name":"BBC News World","state":"UP","lastAttemptAt":"2026-11-19T07:28:39.058298Z","lastSuccessAt":"2026-11-19T07:28:39.259179Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":62,"pollIntervalSeconds":60},{"id":3,"name":"The Verge","state":"DEGRADED","lastAttemptAt":"2026-11-19T07:28:39.385849Z","lastSuccessAt":"2026-11-19T07:20:04.965799Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":577,"pollIntervalSeconds":60},{"id":4,"name":"Yahoo News Top Stories","state":"UP","lastAttemptAt":"2026-11-19T07:28:39.624088Z","lastSuccessAt":"2026-11-19T07:28:40.016459Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":61,"pollIntervalSeconds":60},{"id":5,"name":"MarketWatch Top Stories","state":"UP","lastAttemptAt":"2026-11-19T07:28:40.056049Z","lastSuccessAt":"2026-11-19T07:28:40.439103Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":61,"pollIntervalSeconds":60},{"id":6,"name":"Bloomberg Markets","state":"UP","lastAttemptAt":"2026-11-19T07:28:40.466947Z","lastSuccessAt":"2026-11-19T07:28:41.338108Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":60,"pollIntervalSeconds":60},{"id":7,"name":"Investing.com News","state":"DEGRADED","lastAttemptAt":"2026-11-19T07:28:41.393104Z","lastSuccessAt":"2026-11-19T07:26:29.637288Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":192,"pollIntervalSeconds":60},{"id":8,"name":"FXStreet News","state":"DEGRADED","lastAttemptAt":"2026-11-19T07:28:41.514537Z","lastSuccessAt":"2026-11-19T07:20:09.651617Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":572,"pollIntervalSeconds":60},{"id":9,"name":"Federal Reserve Press Releases","state":"DEGRADED","lastAttemptAt":"2026-11-19T07:28:41.661752Z","lastSuccessAt":"2026-11-19T07:20:11.210301Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":570,"pollIntervalSeconds":60},{"id":10,"name":"Bank of Canada Press Releases","state":"DEGRADED","lastAttemptAt":"2026-11-19T07:28:41.799020Z","lastSuccessAt":"2026-11-19T07:20:12.171176Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":569,"pollIntervalSeconds":60},{"id":11,"name":"CNBC Top News","state":"UP","lastAttemptAt":"2026-11-19T07:28:42.210311Z","lastSuccessAt":"2026-11-19T07:28:42.412771Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":59,"pollIntervalSeconds":60}]}```

## Step 6 — Verify
- git status --short: (empty)
- HEAD: 5281794eeed0161d34fadc970d3a1ab4044f9138
- mvn clean verify: BUILD SUCCESS
- Tests: 33 run, 0 failures, 0 errors (before: 28)
- SpotBugs: BugInstance size is 0
- free -h / load (post-verify): see below

```
 02:35:58 up 6 days, 44 min,  1 user,  load average: 7.95, 6.40, 6.85
               total        used        free      shared  buff/cache   available
Mem:            14Gi        12Gi       870Mi       1.3Gi       2.9Gi       2.2Gi
Swap:           15Gi        12Gi       3.9Gi

## Fix addendum — poll-success semantics

### Distinction paragraph
`last_success_at` and the ETag/Last-Modified validators answer different questions and follow different rules. The validators are conditional-GET bookmarks: they persist only in the same transaction as a successful ingest, because advancing them on un-ingested content would let a later 304 hide items that were never stored. `last_success_at` is a health timestamp: it records the last correctly completed poll, including an HTTP 304 (nothing new) and an HTTP 200 whose items are all duplicates (zero new rows), because those are successful polls and the source is healthy. It does not advance on fetch/network error, HTTP error status, parse failure, or robots block. Thus a 304 moves health state but never moves the bookmark.

### Changed tests
No prior seam tests were altered in intent. Added four tests to `SourceHealthSeamTest`: `notModifiedPollReadsUpHttp`, `duplicateItemsPollAdvancesLastSuccessHttp`, `parseFailureDoesNotAdvanceLastSuccessHttp`, `fetchFailureDoesNotAdvanceLastSuccessHttp`.

### New live payload (verbatim)
```json
{"status":"UP","lastCompletedPollCycle":"2026-11-19T07:52:27.237680926Z","heartbeatStale":false,"pollIntervalSeconds":60,"sources":[{"id":1,"name":"BBC News World","state":"UP","lastAttemptAt":"2026-11-19T07:52:24.723018Z","lastSuccessAt":"2026-11-19T07:52:24.903875Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":56,"pollIntervalSeconds":60},{"id":3,"name":"The Verge","state":"UP","lastAttemptAt":"2026-11-19T07:52:24.971095Z","lastSuccessAt":"2026-11-19T07:52:25.113049Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":56,"pollIntervalSeconds":60},{"id":4,"name":"Yahoo News Top Stories","state":"UP","lastAttemptAt":"2026-11-19T07:52:25.116537Z","lastSuccessAt":"2026-11-19T07:52:25.548550Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":55,"pollIntervalSeconds":60},{"id":5,"name":"MarketWatch Top Stories","state":"UP","lastAttemptAt":"2026-11-19T07:52:25.583946Z","lastSuccessAt":"2026-11-19T07:52:25.937907Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":55,"pollIntervalSeconds":60},{"id":6,"name":"Bloomberg Markets","state":"UP","lastAttemptAt":"2026-11-19T07:52:25.940999Z","lastSuccessAt":"2026-11-19T07:52:26.379461Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":54,"pollIntervalSeconds":60},{"id":7,"name":"Investing.com News","state":"UP","lastAttemptAt":"2026-11-19T07:52:26.425212Z","lastSuccessAt":"2026-11-19T07:52:26.564546Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":54,"pollIntervalSeconds":60},{"id":8,"name":"FXStreet News","state":"UP","lastAttemptAt":"2026-11-19T07:52:26.567492Z","lastSuccessAt":"2026-11-19T07:52:26.674875Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":54,"pollIntervalSeconds":60},{"id":9,"name":"Federal Reserve Press Releases","state":"UP","lastAttemptAt":"2026-11-19T07:52:26.677317Z","lastSuccessAt":"2026-11-19T07:52:26.810992Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":54,"pollIntervalSeconds":60},{"id":10,"name":"Bank of Canada Press Releases","state":"UP","lastAttemptAt":"2026-11-19T07:52:26.814235Z","lastSuccessAt":"2026-11-19T07:52:26.954421Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":true,"secondsSinceLastSuccess":54,"pollIntervalSeconds":60},{"id":11,"name":"CNBC Top News","state":"UP","lastAttemptAt":"2026-11-19T07:52:26.957064Z","lastSuccessAt":"2026-11-19T07:52:27.159228Z","lastErrorClass":null,"consecutiveFailures":0,"validatorsPresent":false,"secondsSinceLastSuccess":54,"pollIntervalSeconds":60}]}```

### Verify record
git status --short: (empty)
HEAD: 1abd1fc65d4789ba9177e61f6702a452da609299
mvn clean verify: BUILD SUCCESS
Tests: 37 run, 0 failures, 0 errors
SpotBugs: BugInstance size is 0
Java: 21.0.13.0.3; Maven: 3.9.12

free -h / load:
 02:55:28 up 6 days,  1:03,  1 user,  load average: 7.15, 6.64, 6.57
               total        used        free      shared  buff/cache   available
Mem:            14Gi        12Gi       578Mi       1.2Gi       3.0Gi       2.2Gi
Swap:           15Gi        12Gi       3.3Gi
