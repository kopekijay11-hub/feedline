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
