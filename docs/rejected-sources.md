# Rejected sources

Feedline ships a curated, shrinking set of feed sources. Sources removed from
`src/main/resources/application.yml` are recorded here so a fresh deployment does
not silently re-add them.

## MarketWatch Top Stories (removed 2026-11-22)

- Publisher: Dow Jones (MarketWatch)
- URL: https://feeds.content.dowjones.io/public/rss/mw_topstories
- Reason: the robots.txt endpoint answers HTTP 403 to the production User-Agent
  and Feedline's robots handling is fail-closed, so the feed can never be polled
  in production.
- Reopening trigger: the robots endpoint answers HTTP 200 to the production
  User-Agent.

## CBC Top Stories

See `REPORT-INGEST-FIXES.md` section 5 (CBC diagnostic): UA-blocked at the
Akamai/edge layer (TLS handshake completes, then RST_STREAM for the Feedline
User-Agent; default UA gets HTTP 200). Not re-added.

## Robots-blocked removals (13)

See `REPORT-INGEST-FIXES.md` section 4: CNBC Top News; Google News US/CA/World;
Yahoo Finance Gold/Silver/Platinum/Palladium/Copper/Crude Oil/Natural Gas/S&P 500/
USD/CAD. Removed because their robots.txt disallowed the Feedline agent (or the
endpoint was unreachable) under fail-closed handling.

## Source expansion sweep (REV2) rejects

### NHK World

- Publisher: NHK (Japan)
- URL: https://www3.nhk.or.jp/rss/news/cat0.xml (stale; English feed not found)
- Reason: NO-FEED-FOUND — no current English top-news RSS feed discoverable; the only English feed found (podcast) carries a single item and the domestic cat0 feed is Japanese/stale.
- Reopening trigger: NHK publishes a current English top-news RSS feed that returns 200 and parses with items newer than 7 days.

### Toronto Star

- Publisher: Torstar
- URL: https://www.thestar.com/search/?f=rss&t=article&c=news*&l=50&s=start_time&sd=desc
- Reason: ROBOTS — robots.txt (User-agent: *) Disallows /search/, so the feed path is blocked under fail-closed handling.
- Reopening trigger: robots.txt no longer disallows the feed path.

### National Post

- Publisher: Postmedia
- URL: https://nationalpost.com/feed
- Reason: ROBOTS — robots.txt (User-agent: *) Disallows /feed and /*/feed; no alternate feed found.
- Reopening trigger: robots.txt no longer disallows the feed path or a new official feed is published.

### El Pais English

- Publisher: PRISA
- URL: https://feeds.elpais.com/mrss-s/pages/ep/site/english.elpais.com/portada
- Reason: ROBOTS — robots.txt for feeds.elpais.com returns HTTP 403 to the production User-Agent (fail-closed).
- Reopening trigger: the robots endpoint answers HTTP 200 to the production User-Agent.

### EFE

- Publisher: Agencia EFE
- URL: https://www.efe.com/efe/english/4/rss
- Reason: NO-FEED-FOUND — no current public RSS feed discoverable that returns RSS/Atom with items.
- Reopening trigger: EFE publishes a current public RSS feed that returns 200 and parses.

### Xinhua

- Publisher: Xinhua News Agency
- URL: http://www.xinhuanet.com/english/rss/worldrss.xml
- Reason: STALE — feed returns items but newest is from 2018-01-24.
- Reopening trigger: feed resumes publishing items newer than 7 days.

### News24 (South Africa)

- Publisher: Media24
- URL: https://feeds.news24.com/articles/news24/TopStories/rss
- Reason: NO-FEED-FOUND — feed endpoint no longer resolves (DNS failure) and the replacement endpoint returns a single item with no date.
- Reopening trigger: a current News24 RSS endpoint returns 200 and parses with items.

### Times of Israel

- Publisher: Times of Israel
- URL: https://www.timesofisrael.com/feed/
- Reason: ROBOTS — robots.txt (User-agent: *) Disallows /feed/.
- Reopening trigger: robots.txt no longer disallows the feed path.

### Al Arabiya English

- Publisher: MBC Group
- URL: https://english.alarabiya.net/rss.xml
- Reason: ROBOTS/FETCH-FAIL — robots/feed endpoint returns HTTP 403 to the production User-Agent (Cloudflare challenge).
- Reopening trigger: the endpoint answers HTTP 200 to the production User-Agent.

### Arab News

- Publisher: SRMG
- URL: https://www.arabnews.com/rss.xml
- Reason: ROBOTS — robots.txt endpoint returns HTTP 403 to the production User-Agent (fail-closed).
- Reopening trigger: the robots endpoint answers HTTP 200 to the production User-Agent.

### Japan Times

- Publisher: The Japan Times
- URL: https://www.japantimes.co.jp/feed/
- Reason: ROBOTS — robots.txt endpoint returns HTTP 403 to the production User-Agent (fail-closed).
- Reopening trigger: the robots endpoint answers HTTP 200 to the production User-Agent.

### USA Today

- Publisher: Gannett
- URL: https://rssfeeds.usatoday.com/usatoday-NewsTopStories
- Reason: NO-FEED-FOUND — legacy feed redirects to the homepage and returns no items.
- Reopening trigger: USA Today publishes a current public RSS feed that returns 200 and parses.

### Politico

- Publisher: Politico
- URL: https://www.politico.com/rss/politicopicks.xml
- Reason: ROBOTS — robots.txt endpoint returns HTTP 403 to the production User-Agent (fail-closed).
- Reopening trigger: the robots endpoint answers HTTP 200 to the production User-Agent.

### NZ Herald

- Publisher: NZME
- URL: http://rss.nzherald.co.nz/rss/xml/nzhrsscid_000000001.xml
- Reason: ROBOTS — verified live: the production poller reports "disallowed by robots.txt during fetch" on both poll cycles (consecutiveFailures=2, state=DEGRADED); the feed never completes a real poll.
- Reopening trigger: robots.txt permits the feed path for the production User-Agent and the feed completes a real poll.
