# Feedline

Feedline is a tiny, single-process RSS/Atom feed poller built as a Java
learning showcase. It proves a small but complete Spring Boot + PostgreSQL
stack: a scheduled poller that fetches one remote feed, normalizes it with
Rome, and upserts the items into PostgreSQL.

## What it uses

- **Spring Boot 3.x** (Java 21) with `spring-boot-starter-web` and
  `spring-boot-starter-data-jpa`
- **PostgreSQL** driver + Flyway for schema migrations
- **com.rometools:rome** for parsing feeds
- **Testcontainers** for the integration tests
- **SpotBugs** wired into the Maven build

No paid services, no cloud, no API keys. Everything is open source.

## The three-command run

1. `docker compose up -d` — start PostgreSQL (compose file `docker-compose.yml`,
   image `postgres:16-alpine`). A healthcheck waits for the DB.
2. `mvn spring-boot:run` — start Feedline. Flyway migrates the schema on first
   start and the `@Scheduled` poller begins pulling the configured feed every
   60 s.
3. `docker compose logs -f` — watch the poller output.

The feed is configured in `application.yml`:

- primary: `https://www.cbc.ca/webfeed/rss/rss-topstories`
- fallback: `https://feeds.bbci.co.uk/news/world/rss.xml`

The fetcher tries the primary first and, only if it does not return valid RSS,
falls back to the BBC feed. The poller also seeds the `feed_sources` registry
row so the DB starts empty on first run.

## Architecture (one line)

Feedline is a single Spring Boot process: a scheduled poller (fixed delay,
configurable via `feed.poll-delay`) fetches one configured feed, normalizes its
Rome `SyndEntry` records into `Item` entities, and upserts them into PostgreSQL
using the unique `items(source_id, guid)` constraint as insert-if-absent.

## Tests

- Unit tests: `mvn test` — feeds the normalizer with a captured real feed XML
  (stored under `src/test/resources/fixtures/`) plus a synthetic entry without a
  guid.
- Integration tests: `mvn verify` — Testcontainers-backed PostgreSQL tests
  (saves + reads back, and an insert-if-absent assertion). The single
  `mvn verify` command runs both.

## About this build

Feedline was produced by an operator directing coding agents: the source, tests
and documentation were written and verified by agents, and the operator checked
the results against the tests (see REPORT-M1.md). This is a **learning
showcase, not production-grade software**. If a test cannot pass without
weakening it, that fact is reported as NOT RUN instead of being hidden.
