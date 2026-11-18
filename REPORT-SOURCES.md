# REPORT-SOURCES — Source expansion (Job 2)

- Branch: `source-expansion`
- Base: `origin/main` @ `0d334b5727e343fcba723620f88b9b603258c72c` (fetched at start of run)
- Docker availability (this session): **available** (`docker info` OK)
- Change type: **configuration only** (`src/main/resources/application.yml`), following existing entries' shape exactly. No code changes were required.

## Verification protocol (per source, before adding)

- Fetch method: `curl -sS -L --max-redirs 3` with identified Feedline User-Agent `Feedline/0.1 (+https://github.com/kopekijay11-hub/feedline)` (never spoofed browser UA). Recorded HTTP status, final URL after redirects, item count, newest item's date.
- Evidence bar: document parses as RSS/Atom; newest item within **72 hours** for news sources, within **31 days** for Federal Reserve and Bank of Canada.
- No improvised substitutions for failures (skipped with evidence). "App booting and ingesting on the new config" **not run** in this session; that is left for the morning full verify (test-compile + Docker-free unit tests were run here; see below).

## Per-source results

| # | Name (configured) | Configured URL | Final URL | HTTP | Items | Newest item date (UTC) | Verdict | Reason / notes |
|---|---|---|---|---:|---:|---|---|---|
| 1 | Google News US (Google News proxy) | https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en | https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en | 200 | 38 | 2026-11-18T07:07:00Z | **ADDED** | RSS parses; newest within 72h. Proxy nature explicit in name. |
| 2 | Google News CA (Google News proxy) | https://news.google.com/rss?hl=en-CA&gl=CA&ceid=CA:en | https://news.google.com/rss?hl=en-CA&gl=CA&ceid=CA:en | 200 | 38 | 2026-11-18T07:20:00Z | **ADDED** | RSS parses; newest within 72h. Proxy nature explicit in name. |
| 3 | Google News World (Google News proxy) | https://news.google.com/rss/headlines/section/topic/WORLD?hl=en-US&gl=US&ceid=US:en | https://news.google.com/rss/topics/CAAqKggKIiRDQkFTRlFvSUwyMHZNRGx1YlY4U0JXVnVMVlZUR2dKVlV5Z0FQAQ?hl=en-US&gl=US&ceid=US:en | 200 | 62 | 2026-11-18T06:56:00Z | **ADDED** | Redirect to `/rss/topics/...` observed as expected; **original URL configured**. RSS parses; newest within 72h. Proxy nature explicit in name. |
| 4 | Yahoo News Top Stories | https://news.yahoo.com/rss/topstories | https://news.yahoo.com/rss/topstories | 200 | 13 | 2026-11-18T03:40:49Z | **ADDED** | Identified fetch not refused (200). RSS parses; newest within 72h. |
| 5 | CNBC Top News | https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114 | https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114 | 200 | 30 | 2026-11-18T07:17:11Z | **ADDED** | RSS parses; newest within 72h. |
| 6 | MarketWatch Top Stories | https://feeds.content.dowjones.io/public/rss/mw_topstories | https://feeds.content.dowjones.io/public/rss/mw_topstories | 200 | 10 | 2026-11-18T01:59:00Z | **ADDED** | Non-realtime variant as specified. RSS parses; newest within 72h. |
| 7 | Bloomberg Markets | https://feeds.bloomberg.com/markets/news.rss | https://www.bloomberg.com/feeds/markets/news.rss | 200 | 20 | 2026-11-18T07:15:38Z | **ADDED** | RSS parses; newest within 72h. **Headline stubs expected and acceptable (noted).** |
| 8 | OilPrice | https://oilprice.com/rss/main | https://oilprice.com/rss/main | 500 | 0 | — | **SKIPPED** | HTTP 500, empty/unparseable body (`no element found: line 1, column 0`) with identified UA; fails evidence bar (no parseable RSS/Atom). No substitution. |
| 9 | Investing.com News | https://www.investing.com/rss/news.rss | https://www.investing.com/rss/news.rss | 200 | 10 | 2026-11-18T07:10:27Z | **ADDED** | RSS parses; newest within 72h. |
| 10 | Kitco (official RSS) | — (none pinned that passes) | — | — | — | — | **SKIPPED** | Pinned from Kitco's own site: no discoverable RSS/feed link in homepage markup; probed common official endpoints (`/rss/rss.xml`, `/feed`, `/rss`, `news.kitco.com/rss`) all returned HTTP 404 / non-RSS with identified UA. No official RSS URL found that passes bar. No substitution. |
| 11 | FXStreet (official RSS) | https://www.fxstreet.com/rss (pinned from fxstreet.com site: `/rss` link present) | https://www.fxstreet.com/rss | 200 | 30 | 2026-11-18T07:17:06Z | **ADDED** | Official feed pinned from FXStreet site. RSS parses; newest within 72h. (Also probed `/rss/news`, `/rss/analysis` — both valid; selected general `/rss` as the pinned official feed.) |
| 12 | Federal Reserve Press Releases | https://www.federalreserve.gov/feeds/press_all.xml (pinned from federalreserve.gov/feeds) | https://www.federalreserve.gov/feeds/press_all.xml | 200 | 20 | 2026-11-13T21:15:00Z | **ADDED** | Official "All Press Releases" feed. RSS parses; newest ~4.4 days old, **within 31-day bar** for Federal Reserve. |
| 13 | Bank of Canada Press Releases | https://www.bankofcanada.ca/content_type/press-releases/feed/ (pinned from bankofcanada.ca/rss-feeds/ Press Releases) | https://www.bankofcanada.ca/content_type/press-releases/feed/ | 200 | 10 | 2026-11-16T10:00:04Z (newest item `<dc:date>`) | **ADDED** | Official press-releases feed; parses as RDF/RSS 1.0 (items present). Feed-level/channel `<dc:date>` 2026-11-17T21:22:37Z; newest item 2026-11-16T10:00:04Z — **within 31-day bar** for Bank of Canada. (Note: non-standard root namespace/structure vs typical RSS 2.0/Atom; parse/ingest confirmation deferred to morning full verify.) |
| 14 | Yahoo Finance - Gold (GC=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=GC=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=GC=F&region=US&lang=en-US | 200 | 19 | 2026-11-18T06:30:00Z | **ADDED** | RSS parses; newest within 72h. |
| 15 | Yahoo Finance - Silver (SI=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=SI=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=SI=F&region=US&lang=en-US | 200 | 17 | 2026-11-18T05:34:14Z | **ADDED** | RSS parses; newest within 72h. |
| 16 | Yahoo Finance - Platinum (PL=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=PL=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=PL=F&region=US&lang=en-US | 200 | 18 | 2026-11-18T06:00:00Z | **ADDED** | RSS parses; newest within 72h. |
| 17 | Yahoo Finance - Palladium (PA=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=PA=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=PA=F&region=US&lang=en-US | 200 | 20 | 2026-11-18T01:41:50Z | **ADDED** | RSS parses; newest within 72h. |
| 18 | Yahoo Finance - Copper (HG=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=HG=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=HG=F&region=US&lang=en-US | 200 | 18 | 2026-11-18T04:43:50Z | **ADDED** | RSS parses; newest within 72h. |
| 19 | Yahoo Finance - Crude Oil (CL=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=CL=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=CL=F&region=US&lang=en-US | 200 | 14 | 2026-11-18T06:00:00Z | **ADDED** | RSS parses; newest within 72h. |
| 20 | Yahoo Finance - Natural Gas (NG=F) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=NG=F&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=NG=F&region=US&lang=en-US | 200 | 18 | 2026-11-18T05:23:04Z | **ADDED** | RSS parses; newest within 72h. |
| 21 | Yahoo Finance - S&P 500 (^GSPC) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=%5EGSPC&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=%5EGSPC&region=US&lang=en-US | 200 | 18 | 2026-11-18T06:37:13Z | **ADDED** | RSS parses; newest within 72h. |
| 22 | Yahoo Finance - USD/CAD (CAD=X) | https://feeds.finance.yahoo.com/rss/2.0/headline?s=CAD=X&region=US&lang=en-US | https://feeds.finance.yahoo.com/rss/2.0/headline?s=CAD=X&region=US&lang=en-US | 200 | 12 | 2026-11-17T20:24:13Z | **ADDED** | RSS parses; newest within 72h. |

**Summary:** 20 added, 2 skipped (OilPrice: HTTP 500/unparseable; Kitco: no official RSS endpoint found/passing). No improvised substitutions. Exclusions honored (no CNN direct, no Reuters/AP, no Yahoo Finance general, no MarketWatch realtime).

## What this report does NOT prove

- **The application booting and ingesting on the new config was NOT run in this session.** Confirming the app ingests the newly added sources is the morning full verify's job.
- Docker-free tests and compilation were run (below); Testcontainers-backed integration/smoke and full-boot ingest were not executed in this job.

## Tests run (config-only change)

- `mvn -DskipTests test-compile`: **BUILD SUCCESS**.
- `mvn -Dtest='FeedNormalizerTest,FeedFetcherTest,FeedFetcherStubServerTest' test` (Docker-free unit/stub-server tests): **BUILD SUCCESS**, `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`.
- Docker availability during session: **available** (not required/used for the above Docker-free subset).
- Environment (end of job): `free -h` Mem total 14Gi, used 8.8Gi, free 2.9Gi, shared 1.5Gi, buff/cache 5.0Gi, available 6.2Gi, Swap used 7.1Gi; loadavg `1.50 1.63 1.64`; `docker-ok`.
