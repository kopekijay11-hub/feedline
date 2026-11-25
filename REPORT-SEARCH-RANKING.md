# Report — Dashboard Search Ranking

base: 6bcc38a1b1ef87a9ba5b68bea309d3971776fcad == ls-remote 6bcc38a1b1ef87a9ba5b68bea309d3971776fcad

Deviation: none.

## Implementation summary

Backend (`GET /api/v1/items`):
- Added optional `sort` request parameter (strict binding registers it automatically) on `ItemsController.list`, threaded into `ItemQueryService`.
- `sort` absent or `recent`: byte-identical prior behavior, 2-part cursor, same SQL order and totals.
- `sort=top`: ranked by effective cluster size DESC, then `fetched_at` DESC, then `id` DESC; effective size = per-cluster member count from `story_cluster_members`, COALESCE 1 for unclustered.
- Other values (including `TOP`, empty): 400 problem+json via `IllegalArgumentException`.
- Ranked keyset cursor = Base64URL of `effectiveSize|fetchedAt|id` (3 parts); ranked predicate as specified. 2-part cursor with `sort=top` and 3-part cursor without `sort=top` are malformed (400).
- `total`/`hasMore` unchanged (total = full filtered match count, cursor-independent).
- `attachClusterSizes` unchanged as DTO source of `clusterSize`.

Dashboard (ops.js / index.html):
- `buildItemsQuery` adds `sort=top` when trimmed `q` is non-blank OR Top Stories mode is on; otherwise no sort param.
- Top Stories toggle button (label `Top stories`, title hint) in items filter controls toggles `state.topStories`; Reset clears it along with `q`.
- Items summary appends ` — ranked by story size` whenever the request carried `sort=top`.
- No URL-state change for sort or Top Stories mode; cursor stack machinery untouched.

## Part 4 live-shape evidence

Scratch DB `feedline_search_probe` restored from live DB via JDBC copy (41 sources, 4654 items, 141 clusters, 351 members). Scratch app on port 8081. Clustering state used as copied.

Word chosen: `Nvidia` (search_language english). Match counts: 290 items; cluster-size distribution among matches: size 22 × 22, size 3 × 3, size 2 × 22, size 1 × 243.

Default order (`GET /api/v1/items?q=Nvidia&size=20`), first 5 (id, clusterSize):
- 4653, null
- 4637, null
- 4634, null
- 4632, null
- 4631, null

Ranked (`GET /api/v1/items?q=Nvidia&sort=top&size=20`), first 5 (id, clusterSize):
- 4216, 22
- 3765, 22
- 3584, 22
- 3577, 22
- 3537, 22

Top Stories mode no q (`GET /api/v1/items?sort=top&size=20`), first 5 (id, clusterSize):
- 4216, 22
- 3765, 22
- 3584, 22
- 3577, 22
- 3537, 22

Scratch DB dropped and port 8081 stopped after evidence capture.

## Test inventory

REV2 Part 3 completion pass. Testcontainers only; corpus-dependency class: NONE; no live DB and no tower state. Every test seeds its own sources, items, clusters, and cluster members. No implementation changes were made in this pass.

Clause 1 — API ranking battery, `src/test/java/ca/feedline/ItemsRankedSearchSeamTest.java`:
- `rankedOrder_groupsByEffectiveClusterSizeThenFetchedAtThenId` — clause 1a
- `rankedPagination_walksNextCursorWithoutDuplicatesOrSkips` — clause 1b
- `defaultAndRecent_modesHaveIdenticalRecentOrder` — clause 1c
- `topStoriesMode_withoutQueryRanksWholeListing` — clause 1d
- `invalidSortAndCursorModeMismatches_return400` — clause 1e
- `rankedSourcesFilter_ranksWithinOneSourceOnly` — clause 1f

Clause 2 — dashboard served-asset seam, `src/test/java/ca/feedline/OpsDashboardSeamTest.java`:
- `opsJsAddsSortTopWhenQueryPresent` — clause 2(i)
- `topStoriesToggleSendsSortTopWithoutQuery` — clause 2(ii)

Dashboard assertions inspect the MockMvc-served `/index.html` and `/ops.js` bodies in the existing static-asset seam style. No JS test tooling was added.

## Verify

`mvn clean verify` on the branch tip, with `git status --short` checked empty before and after the run:
- Tests run: 213, Failures: 0, Errors: 0, Skipped: 0
- SpotBugs BugInstance size is 0
- BUILD SUCCESS
- git status --short: (empty) — clean working tree
