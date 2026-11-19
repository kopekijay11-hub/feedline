# REPORT-INGEST-FIXES — Feedline ingest fixes

Branch: `ingest-fixes`
Base SHA: `ecd71a10ecc1a19fe2c6fdb4332b26c60904c7f4`
Tip SHA: `539d4330ebd07f7de8a1d2ebc3235201676898d0`

## Section 1 — Scheduling fixes + regression test
- 1. `@EnableScheduling` added to `FeedlineApplication` (was missing; app never polled).
- 2. `application.yml`: `feed.poll-delay` "60s" -> "60000". Test overrides "999999h" -> "86400000" in `ApplicationContextSmokeTest` and `FeedPollerFixtureIntegrationTest` (and `FeedPollerDedupeIntegrationTest`).
- 3. New test `SchedulerInvokesPollerTest`: scheduler fires poller with no direct call; counting stub + 10s timeout. Mutation: remove `@EnableScheduling` -> FAILS (1 failure); restore -> green.
- Commit: `81ec2c2`. Suite green.

## Section 2 — BOM-tolerant parsing
- 1. Strip leading UTF-8 BOM in `FeedPollerService.parse(String)` (line 244-249).
- 2. Fixture test `bomPrefixedFeed_ingestsIdenticallyToClean` in `FeedPollerFixtureIntegrationTest`: BOM-prefixed `dedupe-test.xml` ingests same count + GUIDs. Pass.
- Commit: `292ab68`. Suite green.

## Section 3 — Validator-persistence investigation (verdict: code did NOT conform; fixed)
- Citation: `FeedPollerService.pollSingleSource` lines 150-205. `parse()` (line 244) previously swallowed exceptions and returned `List.of()`, so the outer `try/catch` around parse never triggered; validators updated unconditionally at lines ~198-205 (`if (result.hasNewValidators()) { source.setEtag(...); ... }`). Thus on a parse failure validators persisted -> 304s afterward.
- Fix: `parse()` now declares `throws Exception` and propagates; on failure `pollSingleSource`'s catch (line 170) returns 0 before validator update. Regression test `validatorsNotPersistedOnParseFailure` asserts second request carries no `If-None-Match`; passes after fix, fails before (mutation verified).
- Deployment note: any DB holding Federal Reserve validators from the failed parse must have them cleared (UPDATE feed_sources SET etag=NULL, last_modified=NULL WHERE name='Federal Reserve Press Releases'), else server keeps answering 304.
- Commit: `a55cf61` + `539d433` (null-safety). Suite green (SpotBugs BugInstance size 0).

## Section 4 — Config: remove robots-blocked sources
- Before: 23 sources. After: 10 sources. Removed: CNBC Top News; Google News US/CA/World; Yahoo Finance Gold/Silver/Platinum/Palladium/Copper/Crude Oil/Natural Gas/S&P 500/USD/CAD. Remaining 10: CBC Top Stories, BBC News World, The Verge, Yahoo News Top Stories, Bloomberg Markets, Investing.com News, MarketWatch Top Stories, FXStreet News, Federal Reserve Press Releases, Bank of Canada Press Releases.
- `REPORT-SOURCES.md` untouched.
- Commit: `7df67b1`. Suite green.

## Section 5 — CBC diagnostic (read-only)
- (a) Feedline UA: TLS handshake completed, HTTP/2 request sent, then `HTTP/2 stream 1 was not closed cleanly: INTERNAL_ERROR (err 2)` (curl exit 92). Stage reached: TLS + HTTP request, stream reset by server/CDN.
- (b) Default UA: HTTP/2 200, 25454 bytes, content-type application/xml.
- Classification: UA-blocked (Akamai/edge returns 200 for default UA but RST_STREAM for Feedline UA). Finding only; user-agent not changed.

## Section 6 — Verification of record + bounded live re-check
- Clean tree before verify: `git status --short` empty; HEAD `7df67b1...`.
- Gate: `mvn clean verify` (exact command). Java 21.0.13.0.3, Maven 3.9.12. Verdict: `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`; SpotBugs `BugInstance size is 0`; `BUILD SUCCESS`.
- Bounded re-check: fresh compose DB, app run ~7.5 min (7 cycles). Scheduler fired unassisted; Federal Reserve item rows = 20 (>0); exactly 10 sources attempted. Clean shutdown. Memory: free -h at run time ~4.3 Gi available; load ~2.0.

## Environment
- free -h (final): 14Gi total, ~1.4Gi free, ~4.3Gi available; load average ~2.0.
