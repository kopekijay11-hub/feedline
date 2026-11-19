# REPORT-M2B-API.md

Branch: `m2b-api` (from `origin/main` / `a5bc40c`, no merges).
Scope: Decision 8 API pins — generated full-text search, `/api/v1/items` endpoints, HTTP-seam tests, springdoc OpenAPI, compose polish, bounded live check, verify.

## Step 1 — Search storage

`src/main/resources/db/migration/V5__add_search_vector.sql` adds one `tsvector` column and one GIN index:

```sql
ALTER TABLE items
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(summary, ''))
    ) STORED;

CREATE INDEX idx_items_search_vector ON items USING GIN (search_vector);
```

The migration comment records the language config (`english`) and the invariant: the column is `GENERATED ALWAYS ... STORED`, so Postgres maintains it on every insert/update. No trigger and no application code writes it, so it cannot go stale. The survey columns are `title` and `summary`; the timestamp is `fetched_at` (TIMESTAMP NOT NULL). Evidence of correctness: the live app booted on a fresh database with `ddl-auto: validate`, and the post-ingest `?q=Zelenskyy` capture below returned the newly ingested row immediately (Step 6).

## Step 2 — Endpoints

- `GET /api/v1/items` (`ItemsController.java:33`) — keyset pagination on `(fetched_at, id)` with `ORDER BY fetched_at DESC, id DESC`, opaque Base64URL cursor, `size` default 20 and cap 100, response `{items, nextCursor, total, hasMore}`.
- Filters: `source` (source id), `q` (`search_vector @@ plainto_tsquery('english', :q)`), `from`/`to` on `published_at`. Null policy: comparisons against `NULL` are UNKNOWN, so rows with `published_at IS NULL` are excluded from any date-range result; stated in the OpenAPI operation description and proved by `nullPublished_defaultListingAndDateRangeExclusion`.
- `GET /api/v1/items/{id}` (`ItemsController.java:74`) — DTO or 404 problem+json; a non-numeric id is a 400 problem+json via `ApiExceptionHandler`.
- Strict binding: `StrictParameterInterceptor` on `/api/v1/**` rejects any unbound parameter with a 400 problem+json that names the parameter (verified on both `/api/v1/items` and `/api/v1/sources/health`).
- All error bodies are RFC 7807 `ProblemDetail` with `application/problem+json`; no stack traces in responses (asserted in `itemDetail_existingMissingMalformed`).
- Only DTOs are serialized (`ItemDto`, `ItemsPage`); JPA entities are never returned.

## Step 3 — Seam tests (HTTP harness)

Harness reused from the health job: `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers `postgres:16.16-alpine`, stub HTTP feed, `DirtiesContext.AFTER_EACH_TEST_METHOD` (`ItemsApiSeamTest.java`). Eight tests, all green:

| Test | Proof |
| --- | --- |
| `canary_searchAndSourceFilter` | Stub-feed ingest seeds 29 known items; `q=Zelensky` returns exactly the one known title; `source=<id>` returns exactly all 29 rows for that source. |
| `strictBinding_bogusParam400` | `?serach=foo` is 400 problem+json naming `serach` on both API endpoints, never a silent 200. |
| `pagination_boundaryAndWalk` | Default page is 20; cursor walk over 29 rows with size 3 visits all ids exactly once; last page has `hasMore=false` and `nextCursor=null`; a cursor built from the last item's real `(fetchedAt,id)` yields an empty page with the same `total`. |
| `pagination_sizeCapAt100` | 105 ingested rows, `size=1000` returns 100 with `hasMore=true` and `total=105`. |
| `nullPublished_defaultListingAndDateRangeExclusion` | Null-published rows appear in default sort order `(Dated item, Null published two, Null published one)`; `from`, `to`, and `from`+`to` each return only the dated row (`total=1`). |
| `itemDetail_existingMissingMalformed` | Existing id 200 with matching id; missing id 404 problem+json; malformed id 400 problem+json; neither error body contains a stack trace. |
| `searchStaleness_guardGeneratedColumn` | Stub feed is changed to a unique token *after* migration, ingested, and immediately findable by `q` (and by id) with `total=1`. |
| `openApi_docsContainsPaths` | `/v3/api-docs` is 200 and contains both `/api/v1/items` paths plus the keyset/null-policy description text. |

Baseline defect found and fixed on the way: the branch tip inherited from the prior session was red (44 tests, 1 error) because `SchedulerInvokesPollerTest` excludes JPA autoconfiguration and the new `ItemQueryService` requires an `EntityManager`; it now declares a `@MockBean`. Six introduced SpotBugs findings were also reduced to zero (defensive copies in `ItemsPage`, narrowed cursor exception types, simpler null checks, one targeted exclusion for the injected `EntityManager`).

## Step 4 — OpenAPI

`springdoc-openapi-starter-webmvc-ui:2.6.0` generates the spec from the code; there is no hand-maintained spec file. `@Operation` on both item endpoints documents keyset pagination, `(fetched_at, id)` sort, 20/100 page sizing, `total`/`hasMore`, and the null-published exclusion policy. Test `openApi_docsContainsPaths` asserts the paths and that description text are present; the live path list is captured in Step 6.

## Step 5 — Compose polish

`docker-compose.yml` keeps exactly one service (postgres) and the named volume `feedline_db_data` used by the jobs' fresh-database recipe. The healthcheck now uses `pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB` with `start_period: 10s`, so it follows the same defaults instead of a second copy of the literals. Application datasource defaults (`application.yml:5-9`) remain `jdbc:postgresql://localhost:5432/feedline`, user/password `feedline`/`feedline` — the exact credentials the compose service creates. No new services were added and no existing volume was deleted; the live check used an out-of-tree override with a new named volume.

## Step 6 — Bounded live check

Fresh compose database: out-of-tree override (`/tmp/opencode/m2b-api-live-override2.yml`) created volume `feedline_feedline_api_live2_data`; before app start `to_regclass('flyway_schema_history')` was NULL. App launched from the committed jar at 2026-11-19 03:52:08 EST with the `application.yml` datasource defaults passed explicitly. Uptime at capture: 03:05; three scheduled poll cycles (03:52:14, 03:53:17, 03:54:20), first cycle 10 sources / 185 items. SIGTERM at 03:55:37 produced the JPA/Hikari shutdown log, then `docker compose down` removed only the container/network (no `-v`). No processes left running.

First page — `$ curl -sS 'http://localhost:8080/api/v1/items?size=3'` (verbatim):

```json
{"items":[{"id":185,"sourceId":11,"guid":"108554277","link":"https://www.cnbc.com/2026/11/18/jeffries-house-speaker-congress.html","title":"Jeffries wins Democratic House speaker race, on track to make history in January","summary":"Jeffries will still need to win a floor vote in January to officially become House speaker.","publishedAt":"2026-11-18T18:32:39Z","fetchedAt":"2026-11-19T08:52:14.858578Z"},{"id":184,"sourceId":11,"guid":"108553680","link":"https://www.cnbc.com/2026/11/18/nvidia-kress-jensen-huang-return.html","title":"Nvidia’s Kress faces a divided workforce as some employees push for Huang’s return","summary":"Nvidia's interim CEO Colette Kress calmed investors. Now she has to calm employees.","publishedAt":"2026-11-18T18:11:41Z","fetchedAt":"2026-11-19T08:52:14.858578Z"},{"id":183,"sourceId":11,"guid":"108554149","link":"https://www.cnbc.com/2026/11/18/tech-outage-costco-panic-buying.html","title":"Tech outages are causing people to panic-buy. Costco is benefiting, Mizuho says","summary":"Shoppers are spending more at Costco as tech outages turn into a race to get emergency goods.","publishedAt":"2026-11-18T16:33:14Z","fetchedAt":"2026-11-19T08:52:14.858578Z"}],"nextCursor":"MjAyNi0xMS0xOVQwODo1MjoxNC44NTg1NzhafDE4Mw","total":185,"hasMore":true}
```

Search — `$ curl -sS 'http://localhost:8080/api/v1/items?q=Zelenskyy&size=1'` (verbatim):

```json
{"items":[{"id":158,"sourceId":11,"guid":"108554822","link":"https://www.cnbc.com/2026/11/19/elon-musk-zelenskyy-starshield-ukraine.html","title":"Musk accuses Zelenskyy of lying about Starshield restrictions in Ukraine","summary":"Elon Musk was asked about Zelenskyy claims over the Starshield network, and he said, \"I don't want to blow your mind, but Zelenskyy is not always truthful.\"","publishedAt":"2026-11-19T06:07:29Z","fetchedAt":"2026-11-19T08:52:14.858578Z"}],"nextCursor":"MjAyNi0xMS0xOVQwODo1MjoxNC44NTg1NzhafDE1OA","total":2,"hasMore":true}
```

OpenAPI path list — `$ curl -sS http://localhost:8080/v3/api-docs | python3 -c 'import json,sys; print("\n".join(json.load(sys.stdin)["paths"]))'` (verbatim):

```
/api/v1/sources/health
/api/v1/sources/health/{sourceId}
/api/v1/items
/api/v1/items/{id}
```

## Step 7 — Verify

- `git status --short` before final verify: (empty)
- HEAD verified: `87da90906206eae244c17d94ab9597eb40356405`
- `mvn clean verify`: **BUILD SUCCESS**
- Tests: **45 run, 0 failures, 0 errors, 0 skipped** (before: 37 at health tip `a5bc40c`; intermediate API seam commits reached 44 tests with one JPA-context error before the fix).
- Per-class: ItemsApiSeamTest 8, SourceHealthSeamTest 9, FeedPollerFixtureIntegrationTest 6, FeedNormalizerTest 6, FeedFetcherStubServerTest 6, FeedPollerIntegrationTest 4, FeedPollerDedupeIntegrationTest 2, FeedFetcherTest 2, SchedulerInvokesPollerTest 1, ApplicationContextSmokeTest 1.
- SpotBugs verdict (verbatim): `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`.

Tool versions: Java 21.0.13.0.3 (Ubuntu), Apache Maven 3.9.12, Spring Boot 3.3.5 / Spring 6.1.14, Surefire/Failsafe 3.2.5, SpotBugs plugin 4.8.6.0, Flyway 11.20.3, Testcontainers 1.21.4, springdoc 2.6.0, Docker 29.1.3, Compose 2.40.3+ds1-0ubuntu1, PostgreSQL 16.16-alpine.

Load and memory before final verify:

```
 03:55:53 up 6 days,  2:04,  1 user,  load average: 1.05, 1.75, 2.15
               total        used        free      shared  buff/cache   available
Mem:            14Gi       7.6Gi       3.9Gi       1.1Gi       5.0Gi       7.4Gi
Swap:           15Gi       5.3Gi        10Gi
```

Load and memory during live capture (03:55:06):

```
 03:55:06 up 6 days,  2:03,  1 user,  load average: 0.77, 1.84, 2.20
               total        used        free      shared  buff/cache   available
Mem:            14Gi       8.0Gi       3.5Gi       1.1Gi       5.0Gi       7.0Gi
Swap:           15Gi       5.3Gi        10Gi
```

One heavy process at a time was observed throughout; no merges, no cleanup deletions, no checkout discards, and no historical report edits were made.
