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
