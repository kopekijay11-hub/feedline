# Source Closeout Report

Branch: source-closeout
Base: ingest-fixes tip 5478dc5

## Step 1 — Remove CBC

Removed "CBC Top Stories" from src/main/resources/application.yml.
Source count: 10 -> 9.
Suite: mvn test -> Tests run: 28, Failures: 0, Errors: 0, Skipped: 0; BUILD SUCCESS.
Commit: 1c9968d chore: remove CBC Top Stories source (10 -> 9)

## Step 2 — CNBC retry (www host form)

robots.txt (https://www.cnbc.com/robots.txt): HTTP 200.
Relevant rule for User-agent: *:

```
User-agent: *
Disallow: /preview/
Disallow: /undefined/
Disallow: /proplayer
Disallow: /appchart/*
Disallow: /search/*
Disallow: /search
Disallow: /zephr/*
Disallow: /blaize/*
```

No Disallow rule bears on /id/ paths, so the path is allowed.

Feed fetch (https://www.cnbc.com/id/100003114/device/rss/rss.html) with production UA Feedline/0.1 (+https://github.com/kopekijay11-hub/feedline): HTTP 200, 20613 bytes, Content-Type: application/xml.
Rome parse: ROME_OK entries=30.
Newest item date: Wed Nov 18 21:49:35 EST 2026 (Thu, 19 Nov 2026 02:49:35 GMT), within 24 hours.

Verdict: added "CNBC Top News" with the www URL. Source count: 9 -> 10.
Commit: f66ac26 chore: add CNBC Top News source via www host (9 -> 10)

## Step 3 — Bounded live check

Fresh compose database. App run ~3 minutes (3 poll cycles).
Log: 3 "Poll cycle completed: 10 sources processed".
CBC attempts: 0.
CNBC item rows: 30 (database).
All 10 configured sources attempted:
- https://feeds.bbci.co.uk/news/world/rss.xml
- https://www.theverge.com/rss/index.xml
- https://news.yahoo.com/rss/topstories
- https://feeds.content.dowjones.io/public/rss/mw_topstories
- https://feeds.bloomberg.com/markets/news.rss
- https://www.investing.com/rss/news.rss
- https://www.fxstreet.com/rss
- https://www.federalreserve.gov/feeds/press_all.xml
- https://www.bankofcanada.ca/content_type/press-releases/feed/
- https://www.cnbc.com/id/100003114/device/rss/rss.html
Clean shutdown via SIGTERM observed (Shutdown hook closed JPA/Hikari).

Database item counts:
BBC News World 33
Bank of Canada Press Releases 10
Bloomberg Markets 20
CNBC Top News 30
FXStreet News 34
Federal Reserve Press Releases 20
Investing.com News 10
MarketWatch Top Stories 10
The Verge 10
Yahoo News Top Stories 11

## Step 4 — Verify

git status --short: (empty)
HEAD: f66ac260bb3d0ceced5db787515eac1c06c0aa1a
mvn clean verify: BUILD SUCCESS
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
SpotBugs verdict: BugInstance size is 0
Java: openjdk 21.0.13.0.3
Maven: 3.9.12

free -h:
              total        used        free      shared  buff/cache   available
Mem:            14Gi        10Gi       1.3Gi       2.3Gi       5.5Gi       4.4Gi
Swap:           15Gi       6.6Gi       9.4Gi
load average: 2.96 1.92 1.98

## REV2 add-on (2026-11-18)

Executed after original source-closeout. No merges. No source substitutions.

### Step A — OilPrice retry

Protocol v2 for https://oilprice.com/rss/main.

robots.txt (https://oilprice.com/robots.txt): HTTP 200. Relevant rules for User-agent: * include Disallow: /cgi-bin/, /search, /goto/, /feed/3, /newsfeeds/redirect/, etc. No Disallow rule bears on /rss/main, so the path is allowed.

Feed fetch (https://oilprice.com/rss/main) with production UA Feedline/0.1 (+https://github.com/kopekijay11-hub/feedline): HTTP 500, 0 bytes (empty body). Re-probed 3 times; all HTTP 500, 0 bytes.

Parse stage: not reached (no body).
Freshness stage: not reached.

Verdict: not added. Source count unchanged.

Verbatim evidence:
```
HTTP/2 500
content-length: 0
```

### Step B — Kitco discovery

Discovery pass: fetched https://www.kitco.com/ (HTTP 200, 280757 bytes) and https://www.kitco.com/news/ (HTTP 200 after 308, 236531 bytes). Searched site markup for official feed links (link rel alternate, rss/atom/feed/xml URLs). Result: no official Kitco feed link discovered in homepage or news section markup.

Re-probe of four endpoints under production UA:
- https://www.kitco.com/rss/rss.xml -> HTTP 404, 61203 bytes (HTML 404 page)
- https://www.kitco.com/feed -> HTTP 404, 61196 bytes (HTML 404 page)
- https://www.kitco.com/rss -> HTTP 404, 61195 bytes (HTML 404 page)
- https://news.kitco.com/rss -> HTTP 404, 6200 bytes (HTML 404 page)

Verdict: no official feed discoverable; Kitco stays out. No Kitco source added.

### Step C — Bounded live check

Fresh compose database. App run ~3 minutes (3 poll cycles). Log: 3 "Poll cycle completed: 10 sources processed". Every configured source attempted (10). Clean shutdown via SIGTERM observed (Shutdown hook closed JPA/Hikari).

Database item counts:
BBC News World 33
Bank of Canada Press Releases 10
Bloomberg Markets 21
CNBC Top News 30
FXStreet News 30
Federal Reserve Press Releases 20
Investing.com News 24
MarketWatch Top Stories 12
The Verge 10
Yahoo News Top Stories 12

No sources added in Steps A-B, so the "item rows > 0 for added sources" condition is vacuously satisfied; all 10 configured sources store > 0 items.

### Step D — Re-verify

git status --short: (empty)
HEAD: 349c01273b88ab24ee2fb009b2de0c15e0b81fc4

mvn clean verify: BUILD SUCCESS
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
SpotBugs verdict: BugInstance size is 0

Source count: before 10, after 10 (no additions).
