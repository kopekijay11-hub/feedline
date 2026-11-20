# REPORT-INGEST-PROOF — Feedline live ingest proof

Branch: `ingest-proof`
Base SHA: `ecd71a10ecc1a19fe2c6fdb4332b26c60904c7f4`
Tip SHA: `044c0fc4eca2c3fb3c7b1c3a4738b6eeaf2b3e3b`
Run window (UTC): start 2026-11-19T00:52:50Z, end 2026-11-19T01:58:33Z (~65.7 min, 61 poll cycles, poll-delay 60000 ms).

## Preconditions
- `git status --short` empty after shakeout commit; HEAD recorded above.
- Fresh database: `docker compose down -v` then `up -d postgres`; datasource `jdbc:postgresql://localhost:5432/feedline` (matches application.yml).
- App built via `mvn -q package -DskipTests` (exit 0).

## Shakeout (Phase 0) fixes (config/bootstrap only)
1. `FeedlineApplication`: added `@EnableScheduling` (one-line bootstrap). Without it `@Scheduled` was never activated, so the app never polled. Documented as infrastructure fix, not source-specific.
2. `application.yml`: `feed.poll-delay` changed from `60s` to `"60000"` (ms). `@Scheduled fixedDelayString` requires a millisecond integer; `60s` caused `NumberFormatException`.
3. Tests (`ApplicationContextSmokeTest`, `FeedPollerFixtureIntegrationTest`): `feed.poll-delay` override `999999h` -> `86400000` (ms) for the same reason.
4. No source-specific code changes were made.

## Shakeout anomaly (code change needed — not fixed)
- Federal Reserve Press Releases feed body begins with a UTF-8 BOM (`EF BB BF`) before the XML declaration. Rome raises `ParsingFeedException: Invalid XML: Content is not allowed in prolog.` Evidence: `onecycle.log` (first cycle, fetch HTTP 200, then parse failure). Result: 0 items stored; validators persisted, so later cycles return 304 and the feed never ingests. Fix would require BOM stripping in `FeedPollerService.parse` (code change) — not applied per shakeout rule.
- Bank of Canada (RSS 1.0/RDF) ingested successfully: 10 items, 0 null guids, sane published dates. PASS for A1.

## Evidence — database (ground truth) + app log
Per source (61 poll cycles):

| Source | Item rows | Null guid | Distinct guid | Earliest published | Latest published | Polls attempted | HTTP 200 | HTTP 304 | HTTP other | Parse failures |
|---|---:|---:|---:|---|---|---:|---:|---:|---:|---:|
| BBC News World | 36 | 0 | 36 | 2026-11-17 21:04 | 2026-11-19 01:05 | 61 | 61 | 0 | 0 | 0 |
| Bank of Canada Press Releases | 10 | 0 | 10 | 2026-06-25 15:00 | 2026-11-16 10:00 | 61 | 1 | 60 | 0 | 0 |
| Bloomberg Markets | 30 | 0 | 30 | 2026-11-18 09:45 | 2026-11-19 01:44 | 61 | 33 | 28 | 0 | 0 |
| CBC Top Stories | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (HTTP 0 network) | 0 |
| CNBC Top News | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| FXStreet News | 34 | 0 | 34 | 2026-11-18 15:00 | 2026-11-19 01:35 | 61 | 3 | 58 | 0 | 0 |
| Federal Reserve Press Releases | 0 | 0 | 0 | — | — | 61 | 0 | 61 | 0 | 1 (BOM, onecycle run) |
| Google News CA | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Google News US | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Google News World | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Investing.com News | 37 | 0 | 37 | (no pubDate) | (no pubDate) | 61 | 3 | 58 | 0 | 0 |
| MarketWatch Top Stories | 10 | 0 | 10 | 2026-11-18 21:19 | 2026-11-19 00:30 | 61 | 31 | 30 | 0 | 0 |
| The Verge | 11 | 0 | 11 | 2026-11-18 18:44 | 2026-11-19 01:39 | 61 | 3 | 58 | 0 | 0 |
| Yahoo Finance - Copper | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - Crude Oil | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - Gold | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - Natural Gas | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - Palladium | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - Platinum | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - S&P 500 | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - Silver | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo Finance - USD/CAD | 0 | 0 | 0 | — | — | 61 | 0 | 0 | 61 (robots.txt blocked) | 0 |
| Yahoo News Top Stories | 13 | 0 | 13 | 2026-11-18 10:10 | 2026-11-19 00:01 | 61 | 61 | 0 | 0 | 0 |

Null guid count = 0 for all 23 sources. Distinct guid = item rows for all sources with items.

## Named assertions
- A1 (Bank of Canada): PASS — 10 item rows, 0 null guids, published dates 2026-06-25..2026-11-16 (sane, not epoch, not future).
- A2 (Dedupe live): PASS — item rows far below polls x items-per-poll. Reconciliation for three highest-volume sources: BBC (61 polls, ~34 items/poll => ~2074 attempts, 36 stored), Bloomberg (61 x ~21 => ~1281, 30 stored), FXStreet (61 x ~30 => ~1830, 34 stored). Unique (source_id, guid) constraint enforced; 0 duplicate guids (distinct = rows).
- A3 (Validators live): PASS — 304 responses observed for multiple sources after first successful cycle: Bloomberg (28), MarketWatch (30), FXStreet (58), The Verge (58), Investing.com (58), Bank of Canada (60). Also Federal Reserve (61, but only because validators persisted from a failed parse).
- A4 (No silent deaths): PASS — all 23 configured sources attempted 61 times each (>=2). Exceptions named: none dropped; blocked/failed sources reported with log evidence above.

## Anomalies / verbatim excerpts
- CBC Top Stories: `Failed to fetch https://www.cbc.ca/webfeed/rss/rss-topstories: HTTP 0` (network/RST) every cycle (61/61). Never ingested.
- CNBC Top News, Google News (x3), Yahoo Finance (x10): `disallowed by robots.txt for generic agent` every cycle (robots.txt `Disallow: /`). Never fetched.
- Federal Reserve: BOM parse failure (see shakeout anomaly). `Failed to parse feed content` / `ParsingFeedException: ... Content is not allowed in prolog.`
- Investing.com: items have NULL published_at (feed provides no pubDate); earliest/latest empty.
- MarketWatch robots.txt returned HTTP 403 but feed itself fetched (robots parser treated 403 as disallowed? Actually feed fetched successfully — robots fetch failed but parser fails open? Log shows `Failed to fetch robots.txt ... HTTP 403` then feed polled). Note: robots parser returns null on non-200/non-404, which `FeedFetcher.checkRobotsAllowed` treats as allowed (fail-open). So MarketWatch ingested despite 403 robots.

## App memory during run
Not sampled (no memory probe in app). Process ran ~65.7 min without restart/OOM; RSS not recorded. (Limitation of this proof.)

## Commit + clean-tree record
- Shakeout commit: `044c0fc4eca2c3fb3c7b1c3a4738b6eeaf2b3e3b` on branch `ingest-proof`.
- Pre-run `git status --short` empty. Post-run tree: report file added (uncommitted until push). See final push.

## What this proof does NOT cover
- API layer (M2b) — not implemented; no HTTP API exercised.
- Feed deaths beyond the ~66-min run window (long-term stability, retries over hours/days).
- Sources blocked by robots.txt are reported as-is; no code changes made to bypass.
- Federal Reserve parse failure requires a code fix (BOM handling) and is not resolved by this proof.
