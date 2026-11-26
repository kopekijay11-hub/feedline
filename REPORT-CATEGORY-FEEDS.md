base: 8bd62611a521794bc23469174913ab1ae8837d94 == ls-remote 8bd62611a521794bc23469174913ab1ae8837d94

# Category RSS Feeds — Completion Report

Branch: `category-feeds`
Base: `8bd62611a521794bc23469174913ab1ae8837d94`

## Changed files
- `src/main/java/ca/feedline/web/CategoryFeedController.java`
- `src/main/java/ca/feedline/service/CategoryFeedService.java`
- `src/main/java/ca/feedline/repository/FeedSourceRepository.java`
- `src/main/java/ca/feedline/repository/ItemRepository.java`
- `src/main/resources/static/index.html`
- `src/main/resources/static/ops.js`
- `README.md`
- `spotbugs-exclude.xml`
- `src/test/java/ca/feedline/CategoryFeedSeamTest.java`

## Endpoints
- `/feed/all.xml`
- `/feed/{category}.xml`
- `/feed/index.opml`

## New tests
`CategoryFeedSeamTest`:
- category-only filtering and 50-item cap
- newest-first ordering
- all-feed spans categories and uses `fetched_at` fallback
- unknown category returns 404
- conditional GET returns 304 with empty body
- hostile text round-trips as inert text
- OPML lists all 10 category/all-feed URLs

## Live evidence
- `/feed/canada.xml`: HTTP 200, 50 items, `ETag`, `Last-Modified`, `Cache-Control: max-age=60`
- matching `If-None-Match`: HTTP 304, empty body
- `/feed/index.opml`: 10 outlines
- unknown slug: HTTP 404

## Verification
Verify of record: `mvn clean verify` on a clean tree at committed branch tip `0ead1ee651298807b9bc40bc417b1e49af626625`, with `git status --short` EMPTY before and after. Surefire verdict: `Tests run: 236, Failures: 0, Errors: 0, Skipped: 0`. SpotBugs verdict: `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`. Result: `BUILD SUCCESS`. Total time: `04:49 min`.

## Part 2
`ops.js` keeps the admin key in browser memory only and clears it explicitly; `index.html` provides the `admin-key` password input with placeholder `X-Admin-Token`. The standing static-content assertion is in `OpsDashboardSeamTest.staticAssetsArePublic()`, which asserts `js` contains `X-Admin-Token` and does not contain the dev token. No new test was required.

## SpotBugs exclusions
`spotbugs-exclude.xml` adds the standing `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` exclusions for `CategoryFeedService`.
