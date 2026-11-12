# REPORT-M1 — Feedline: scaffold + one feed into PostgreSQL

Build date: 2026-11-12 (workspace date). Milestone M1 delivered: project scaffold,
Flyway migration, entities/repositories/normalizer, a scheduled poller that feeds
eexactly one configured feed (CBC first, BBC as fallback), unit tests against a
captured real feed, and Testcontainers integration tests that were written but
could not be executed on this machine.

---

## STEP 0 — Toolchain survey

Ran the four baseline commands exactly as specified, in a plain shell:

| Command | Exit | Result |
|---|---|---|
| `java -version` | 127 | **NOT FOUND** (`java: command not found`). JDK 21 is missing. |
| `mvn -version` | 127 | **NOT FOUND** (`mvn: command not found`). Maven is missing. |
| `docker --version` | 127 | **NOT FOUND** (`docker: command not found`). Docker is missing. |
| `docker compose version` | 127 | **NOT FOUND** (`docker: command not found`). Docker Compose plugin unavailable. |

**Machine state:** no JDK, no Maven, no Docker. Consequently:

- No Java compilation, no Maven build, no tests of any kind were possible.
- `mvn verify` was never executed (never could be).
- The Testcontainers integration test was written and is committed, but it is
  **NOT RUN** — Docker is absent, so no PostgreSQL container can start. Per the
  rules, the test was never faked or deleted.

## Feed selection — which feed was used, and its HTTP status

The real feeds were probed with `curl` (User-Agent `Feedline/1.0 (feedline learn
showcase)`, 10-second timeout), exactly the configuration the poller uses:

| Feed | HTTP status | Bytes | Valid RSS? | Used? |
|---|---|---|---|---|
| `https://www.cbc.ca/webfeed/rss/rss-topstories` (primary) | **200** | **24975** | yes (20 items) | **YES** |
| `https://feeds.bbci.co.uk/news/world/rss.xml` (fallback) | not reached | — | — | not needed |

Result: the **CBC Top Stories feed** was used and returned **HTTP 200 with
24975 bytes of valid RSS** (20 `<item>` entries). The BBC feed was not needed on
this machine. The same URLs (primary then fallback), User-Agent and 10 s timeout
are what the runtime poller connects with.

## Files created

```
feedline/
├── pom.xml                            # Spring Boot 3.3.5 parent, Java 21, all deps + SpotBugs
├── .gitignore
├── docker-compose.yml                 # single PostgreSQL service (postgres:16-alpine)
├── README.md                          # what Feedline is, 3-command run, architecture, honesty note
├── DECISIONS.md                       # title + empty "M1" heading; operator writes entries
├── REPORT-M1.md                       # this file
└── .github/workflows/ci.yml           # JDK 21 setup + `mvn verify` on push
├── src/main/java/ca/feedline/
│   ├── FeedlineApplication.java
│   ├── config/FeedConfig.java         # @Configuration + @Bean @ConfigurationProperties
│   ├── config/FeedProperties.java     # feed.primary-feed-url / fallback-feed-url / poll-delay
│   ├── domain/FeedSource.java         # feed_sources entity
│   ├── domain/Item.java               # items entity, unique (source_id, guid)
│   ├── repository/FeedSourceRepository.java
│   ├── repository/ItemRepository.java
│   └── service/
│       ├── FeedFetcher.java           # UA header, 10 s timeout, primary->fallback, RSS validation
│       ├── FeedNormalizer.java        # guid from entry uri, fallback to link
│       ├── FeedPollerService.java     # @Scheduled fixed delay, insert-if-absent
│       ├── FeedFetchResult.java       # fetch result bean (record)
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V1__initial_schema.sql
└── src/test/
    ├── java/ca/feedline/
    │   ├── FeedNormalizerTest.java    # unit tests against captured fixture
    │   └── FeedPollerIntegrationTest.java  # Testcontainers IT (NOT RUN here)
    └── resources/
        └── fixtures/cbc-top-stories.xml   # captured real CBC feed (24975 bytes)

Note: the files-created listing above lists the service classes; the duplicate `FeedFetchResult.java` line was removed.

## Build / tests

**Exact test command:** `mvn verify`

**Results on this machine — NOT RUN (nothing to report):**

| Test | Status |
|---|---|
| `FeedNormalizerTest` (unit) | **NOT RUN** — JDK 21 and Maven are not installed. |
| `FeedPollerIntegrationTest` (IT, Testcontainers) | **NOT RUN** — Docker is not installed; no PostgreSQL container. |
| `mvn verify` total | 0 tests run, 0 passed, 0 failed, 0 skipped. No counts are available. |

**Skipped/NOT RUN items, named plainly:**

1. The unit test suite (`FeedNormalizerTest`) — class could not be compiled.
2. The Testcontainers integration test (`FeedPollerIntegrationTest`) — class
   could not be compiled **and** Docker is absent anyway.
3. The whole Maven build (`mvn verify` / surefire / failsafe) — never ran.

No test was weakened to make it pass; no test was deleted; nothing was faked.
The integration test remains committed and will run on a machine with JDK 21 +
Maven + Docker.

## How to finish this milestone on another machine

```bash
# 1) Install the toolchain (exact apt commands for Ubuntu 24.04+, adapt for your flavour):
sudo apt update
sudo apt install -y openjdk-21-jdk maven docker.io docker-compose-v2

# optional but recommended: PostgreSQL role/database that matches application.yml
sudo -u postgres psql -c "CREATE ROLE feedline WITH LOGIN PASSWORD 'feedline'; CREATE DATABASE feedline OWNER feedline;"

# 2) (re)start the build:
mvn verify                       # runs unit tests (surefire) and integration tests (failsafe)
```

## Decisions (operator notes for DECISIONS.md)

- Java 21, Maven (BOM 3.3.5), Spring Boot 3.3.5, Postgres 16, Rome 1.18,
  Testcontainers, SpotBugs 4.8.6.0.
- Rome 1.18 exposes the RSS `<guid>` value as `SyndEntry.getUri()`; the fallback
  for a missing guid is `entry.getLink()` as required.
- Insert-if-absent is enforced by the DB unique constraint on `(source_id, guid)`;
  the poller uses a plain `items.save()`.
- The fixture is the real captured CBC feed; the unit test also exercises a
  synthetic entry without a guid.
- `REPORT-M1.md` contains no commit hash (hashes live in chat only).

---

## Machine-readable results block

```json
{
  "milestone": "M1",
  "project": "feedline",
  "toolchain": {
    "java": { "command": "java -version", "exit_code": 127, "found": false, "jdk_21": false, "note": "java: command not found" },
    "mvn": { "command": "mvn -version", "exit_code": 127, "found": false, "maven": false, "note": "mvn: command not found" },
    "docker": { "command": "docker --version", "exit_code": 127, "found": false, "docker": false, "note": "docker: command not found" },
    "docker_compose": { "command": "docker compose version", "exit_code": 127, "found": false, "compose": false, "note": "docker: command not found" }
  },
  "feed": {
    "primary_used": "https://www.cbc.ca/webfeed/rss/rss-topstories",
    "fallback": "https://feeds.bbci.co.uk/news/world/rss.xml",
    "used": "https://www.cbc.ca/webfeed/rss/rss-topstories",
    "http_status": 200,
    "bytes": 24975,
    "valid_rss": true,
    "items_in_feed": 20,
    "status_detail": "valid RSS, 20 items"
  },
  "build": {
    "command": "mvn verify",
    "results": "NOT RUN",
    "tests_run": 0,
    "tests_passed": 0,
    "tests_failed": 0,
    "tests_skipped": 0,
    "skipped_items": [
      "FeedNormalizerTest (JDK 21 and Maven not installed)",
      "FeedPollerIntegrationTest (Docker not installed; cannot run Testcontainers PostgreSQL)",
      "mvn verify / surefire / failsafe (never executed)"
    ]
  },
  "files_created": [
    "pom.xml",
    ".gitignore",
    "docker-compose.yml",
    "README.md",
    "DECISIONS.md",
    "REPORT-M1.md",
    ".github/workflows/ci.yml",
    "src/main/java/ca/feedline/FeedlineApplication.java",
    "src/main/java/ca/feedline/config/FeedConfig.java",
    "src/main/java/ca/feedline/config/FeedProperties.java",
    "src/main/java/ca/feedline/domain/FeedSource.java",
    "src/main/java/ca/feedline/domain/Item.java",
    "src/main/java/ca/feedline/repository/FeedSourceRepository.java",
    "src/main/java/ca/feedline/repository/ItemRepository.java",
    "src/main/java/ca/feedline/service/FeedFetcher.java",
    "src/main/java/ca/feedline/service/FeedNormalizer.java",
    "src/main/java/ca/feedline/service/FeedPollerService.java",
    "src/main/java/ca/feedline/service/FeedFetchResult.java",
    "src/main/resources/application.yml",
    "src/main/resources/db/migration/V1__initial_schema.sql",
    "src/test/java/ca/feedline/FeedNormalizerTest.java",
    "src/test/java/ca/feedline/FeedPollerIntegrationTest.java",
    "src/test/resources/fixtures/cbc-top-stories.xml"
  ],
  "not_run_items": [
    "FeedNormalizerTest",
    "FeedPollerIntegrationTest",
    "mvn verify"
  ],
  "notes": [
    "No JDK 21, no Maven, no Docker on this machine.",
    "Feed used: CBC Top Stories, HTTP 200, 24975 bytes, valid RSS, 20 items.",
    "Testcontainers integration test written and committed; NOT RUN; never faked or deleted."
  ]
}
```
