# REPORT-SOURCE-EXPANSION — Source expansion sweep (REV2)

- Branch: `source-expansion-rev2` (off `main` @ `a7227f8`)
- Final configured source count: **39** (9 pre-existing + 30 added).
- Tallies: ADDED 30, FLAGGED 1, REJECTED 14 (of 45 candidates; 44 listed + AP counted).

## Gate evidence summary

Verification protocol v2 applied per candidate. UA used for all fetches: `Feedline/0.1 (+https://github.com/kopekijay11-hub/feedline)`. Robots fail-closed (403/unreadable = disallowed; 404 = allowed).

## Per-candidate evidence

| # | Candidate | Feed URL | Robots | Fetch HTTP | Items | Body (KB) | 304 | Newest (UTC) | Outcome |
|---|---|---|---|---:|---:|---:|---|---|---|
| 1 | AP via Flipboard | https://flipboard.com/@AssociatedPress.rss | allowed | 200 | 30 | 46 | Y | 2026-11-23T09:06:40Z | ADDED |
| 2 | Al Jazeera English | https://www.aljazeera.com/xml/rss/all.xml | allowed | 200 | 25 | 17 | Y | 2026-11-23T09:33:59Z | ADDED |
| 3 | The Guardian | https://www.theguardian.com/world/rss | allowed | 200 | 45 | 163 | N | 2026-11-23T09:38:08Z | ADDED |
| 4 | New York Times | https://rss.nytimes.com/services/xml/rss/nyt/HomePage.xml | allowed (404 origin) | 200 | 18 | 37 | N/A | 2026-11-23T06:44:00Z | ADDED |
| 5 | Washington Post | https://feeds.washingtonpost.com/rss/world | allowed (404 origin) | 200 | 13 | 9 | N/A | 2026-11-22T21:00:00Z | ADDED |
| 6 | NPR | https://feeds.npr.org/1001/rss.xml | allowed (404 origin) | 200 | 10 | 14 | N/A | 2026-11-23T09:45:21Z | ADDED |
| 7 | DW English | https://rss.dw.com/rdf/rss-en-all | allowed | 200 | 139 | 125 | N/A | 2026-11-23T09:26:00Z | FLAGGED (>100 items) |
| 8 | France 24 English | https://www.france24.com/en/rss | allowed | 200 | 24 | 27 | N/A | 2026-11-23T09:35:45Z | ADDED |
| 9 | NHK World | (no current English top-news feed) | n/a | n/a | 0 | 0 | n/a | n/a | REJECTED (NO-FEED-FOUND) |
| 10 | ABC Australia | https://www.abc.net.au/news/feed/51120/rss.xml | allowed | 200 | 25 | 82 | N | 2026-11-23T09:32:03Z | ADDED |
| 11 | Euronews | https://www.euronews.com/rss?format=xml | allowed | 200 | 50 | 32 | Y | 2026-11-23T09:38:54Z | ADDED |
| 12 | Sky News | https://feeds.skynews.com/feeds/rss/home.xml | allowed | 200 | 5 | 7 | Y | 2026-11-23T02:49:00Z | ADDED |
| 13 | The Globe and Mail | https://www.theglobeandmail.com/arc/outboundfeeds/rss/category/canada/ | allowed | 200 | 100 | 183 | N | 2026-11-23T09:00:00Z | ADDED |
| 14 | CTV News | https://www.ctvnews.ca/arc/outboundfeeds/rss/?outputType=xml | allowed | 200 | 100 | 115 | N | 2026-11-23T09:01:00Z | ADDED |
| 15 | Global News | https://globalnews.ca/feed/ | allowed | 200 | 10 | 20 | Y | 2026-11-22T22:52:08Z | ADDED |
| 16 | Toronto Star | https://www.thestar.com/search/?f=rss&... | disallowed (/search/) | 200 | 50 | 51 | N/A | 2026-11-23T09:06:38Z | REJECTED (ROBOTS) |
| 17 | National Post | https://nationalpost.com/feed | disallowed (/feed) | 200 (HTML) | 0 | 392 | N/A | n/a | REJECTED (ROBOTS) |
| 18 | Radio-Canada | https://ici.radio-canada.ca/info/rss/info | allowed | 200 | 14 | 11 | N | 2026-11-23T09:00:00Z | ADDED |
| 19 | Le Monde English | https://www.lemonde.fr/en/rss/une.xml | allowed | 200 | 14 | 18 | N/A | 2026-11-23T09:22:28Z | ADDED |
| 20 | El Pais English | https://feeds.elpais.com/mrss-s/pages/ep/site/english.elpais.com/portada | disallowed (origin 403) | 200 | 58 | 194 | N/A | 2026-11-22T05:00:00Z | REJECTED (ROBOTS) |
| 21 | Der Spiegel English | https://www.spiegel.de/international/index.rss | allowed | 200 | 20 | 21 | N/A | 2026-11-19T15:19:00Z | ADDED |
| 22 | Tagesschau | https://www.tagesschau.de/xml/rss2 | allowed | 200 | 40 | 55 | N/A | 2026-11-23T09:33:43Z | ADDED |
| 23 | ANSA | https://www.ansa.it/sito/ansait_rss.xml | allowed | 200 | 28 | 18 | N/A | 2026-11-23T09:38:53Z | ADDED |
| 24 | EFE | https://www.efe.com/efe/english/4/rss | n/a | 200 (HTML) | 0 | 280 | n/a | n/a | REJECTED (NO-FEED-FOUND) |
| 25 | The Hindu | https://www.thehindu.com/news/feeder/default.rss | allowed | 200 | 60 | 50 | N | 2026-11-23T09:45:05Z | ADDED |
| 26 | Times of India | https://timesofindia.indiatimes.com/rssfeedstopstories.cms | allowed | 200 | 45 | 46 | Y | 2026-11-23T09:36:57Z | ADDED |
| 27 | South China Morning Post | https://www.scmp.com/rss/feed | allowed | 200 | 50 | 80 | N/A | 2026-11-23T09:39:56Z | ADDED |
| 28 | The Straits Times | https://www.straitstimes.com/news/world/rss.xml | allowed | 200 | 50 | 37 | Y | 2026-11-23T09:12:31Z | ADDED |
| 29 | Japan Times | https://www.japantimes.co.jp/feed/ | disallowed (origin 403) | 200 | 30 | 39 | N/A | 2026-11-23T08:36:00Z | REJECTED (ROBOTS) |
| 30 | Yonhap | https://en.yna.co.kr/RSS/news.xml | allowed | 200 | 100 | 61 | N | 2026-11-23T08:55:20Z | ADDED |
| 31 | Anadolu Agency | https://www.aa.com.tr/en/rss/default?cat=live | allowed | 200 | 30 | 20 | N/A | 2026-11-23T09:46:04Z | ADDED |
| 32 | Xinhua | http://www.xinhuanet.com/english/rss/worldrss.xml | allowed | 200 | 20 | 11 | Y | 2018-01-24T00:00:00Z | REJECTED (STALE) |
| 33 | Africanews | https://www.africanews.com/feed/ | allowed | 200 | 50 | 47 | Y | 2026-11-23T09:29:11Z | ADDED |
| 34 | News24 (South Africa) | https://feeds.news24.com/articles/news24/TopStories/rss | unreachable | DNS fail | 0 | 0 | n/a | n/a | REJECTED (NO-FEED-FOUND) |
| 35 | Times of Israel | https://www.timesofisrael.com/feed/ | disallowed (/feed/) | 200 | 15 | 19 | Y | 2026-11-23T08:20:16Z | REJECTED (ROBOTS) |
| 36 | Al Arabiya English | https://english.alarabiya.net/rss.xml | disallowed (origin 403) | 403 | 0 | 175 | n/a | n/a | REJECTED (ROBOTS) |
| 37 | Arab News | https://www.arabnews.com/rss.xml | disallowed (origin 403) | 403 | 0 | 5 | n/a | n/a | REJECTED (ROBOTS) |
| 38 | RTÉ News | https://www.rte.ie/news/rss/news-headlines.xml | allowed | 200 | 20 | 13 | N/A | 2026-11-23T09:53:46Z | ADDED |
| 39 | NZ Herald | http://rss.nzherald.co.nz/rss/xml/nzhrsscid_000000001.xml | allowed (origin 404) but live-disallowed | 200 | 10 | 19 | N | 2026-11-23T07:35:53Z | REJECTED (ROBOTS — live evidence) |
| 40 | Sydney Morning Herald | https://www.smh.com.au/rss/feed.xml | allowed | 200 | 20 | 18 | N/A | 2026-11-23T09:49:38Z | ADDED |
| 41 | Los Angeles Times | https://www.latimes.com/rss2.0.xml | allowed | 200 | 69 | 76 | N/A | 2026-11-23T06:03:41Z | ADDED |
| 42 | USA Today | https://rssfeeds.usatoday.com/usatoday-NewsTopStories | n/a | redirect to homepage | 0 | 0 | n/a | n/a | REJECTED (NO-FEED-FOUND) |
| 43 | Politico | https://www.politico.com/rss/politicopicks.xml | disallowed (origin 403) | 403 | 0 | 5 | n/a | n/a | REJECTED (ROBOTS) |
| 44 | The Independent | https://www.independent.co.uk/rss | allowed | 200 | 100 | 109 | N/A | 2026-11-23T09:54:31Z | ADDED |
| 45 | Amazon News | https://www.aboutamazon.com/rss/feed.rss | allowed | 200 | 10 | 9 | N/A | 2026-11-19T17:01:00Z | ADDED |

## Conditional-GET flag (gate 4)

Config has no per-source poll interval, so every feed that does not answer `304` is left at the default 60 s poll and flagged here: The Guardian, Washington Post, NPR, ABC Australia, The Globe and Mail, CTV News, Radio-Canada, Le Monde English, Tagesschau, ANSA, The Hindu, South China Morning Post, Yonhap, Anadolu Agency, RTÉ News, Sydney Morning Herald, Los Angeles Times, The Independent, Amazon News, France 24 English, Der Spiegel English, New York Times, Washington Post, DW (flagged), NZ Herald (rejected).

## Live-proof tail (two consecutive polls)

App booted against the compose stack (postgres + kafka). Expanded config (40 sources, including NZ Herald which failed live) completed:

- Cycle 1: 40 sources processed (39 configured + seeding timing), 10 items saved, took **33846 ms**.
- Cycle 2: 40 sources processed, 8 items saved, took **25831 ms**.
- Every ADDED source completed both polls except NZ Herald, which failed live with `robots.txt disallowed` on both cycles and was moved to rejected.
- Zero re-ingests of stable feeds on poll 2; dedupe on (source_id, guid) held. Only genuine new items (8) were saved.
- Outbox drained: `pendingCount=0`, `publishedCount=1861`, `poisonedCount=0`.
- Both cycle durations under 60 s. No heap distress/OOM observed.

## Verify of record

- `mvn clean verify`: **BUILD SUCCESS**.
- Surefire: `Tests run: 174, Failures: 0, Errors: 0, Skipped: 0`.
- SpotBugs: `Done SpotBugs Analysis....` (no violations; check goal passed).

## Known effect

Robots cache TTL is 24 h (default `feed.robots.cache-ttl=1h` actually); all new sources' robots entries expire in the same window each day. Recorded, not fixed here.
