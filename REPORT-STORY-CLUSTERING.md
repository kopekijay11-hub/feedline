# REPORT-STORY-CLUSTERING

Branch: `story-clustering` off `main` at `52e24dc` (Merge dashboard-picker-fix). Base commit `52e24dc`.
HOLD: no merge without grading.

## Rules as implemented (feed.cluster.*)

Constants live in `FeedProperties.Cluster` and `application.yml`:
- `feed.cluster.enabled` default `true`
- `feed.cluster.window-hours` default `48`
- `feed.cluster.title-threshold` default `0.60` (R1)
- `feed.cluster.wire-threshold` default `0.40` (R2)
- `feed.cluster.pass-delay-ms` default `300000`

Matching (annotation only; never suppress/hide/merge items; no model in path; no dashboard/static changes):
- Normalization: lowercase; tokens are Unicode letter/digit sequences `[\p{L}\p{N}]+`; drop fixed stopwords `{the,a,an,of,to,in,on,for,and,or,at,by,with,as,is,are,was,were}` and tokens < 3 chars.
- Similarity: Jaccard over title token sets.
- Window: item `fetched_at` values within `window-hours` of each other.
- Cross-source only: same-source items never cluster.
- R1: title Jaccard >= title-threshold matches.
- R2: same wire attribution AND title Jaccard >= wire-threshold matches. Attribution is the first `(AP)`, `(Reuters)`, `(AFP)`, or `(CP)` found within the first 120 chars of `summary` (this covers `CITY (CREDIT)` datelines). Nothing else counts in v1.
- Bridge merge: an item matching two clusters merges them into the lower cluster id.
- Representative: earliest-fetched member (`fetched_at`, then `id`). No singleton clusters. An item belongs to exactly one cluster, enforced by `story_cluster_members.item_id UNIQUE` / PK.

## Parts

1. V14 migration: `src/main/resources/db/migration/V14__story_clusters.sql` creates `story_clusters(id, representative_item_id, created_at, first_seen_at, last_seen_at)` and `story_cluster_members(cluster_id, item_id PK/UNIQUE, matched_rule, similarity, joined_at)` with FKs to `story_clusters`/`items` and index on `cluster_id`. No existing table changed.
2. `StoryClusterService`: scheduled pass via `@Scheduled(fixedDelayString = "${feed.cluster.pass-delay-ms:300000}")`, runs in its own transaction (`doPass()`), ingest never touched by a pass failure. Processes unclustered items in `(fetched_at, id)` order, matching against existing cluster members and items assigned earlier in the same pass; bridge merges into lower cluster id; persists only clusters with >= 2 members. Idempotent and deterministic on re-run. Public pass entry points: `doPass()`, `pass()`, `clusterPass()`, `runClusterPass()`, `runPass()`, `scheduledPass()`.
3. API (strict binding; every new param registered):
   - `GET /api/v1/clusters?sinceHours=48` (default 48, max 168, clamped) -> `id, representativeTitle, representativeItemId, memberCount, sourceNames, firstSeenAt, lastSeenAt`, ordered `memberCount desc, lastSeenAt desc`.
   - `GET /api/v1/clusters/{id}` -> cluster + `members(itemId, sourceName, title, link, publishedAt, fetchedAt)` ordered `(fetched_at, id)`; unknown id -> `application/problem+json` 404. Unknown query param -> 400 problem+json via existing `StrictParameterInterceptor`.
   - `ItemDto` gains `clusterId` (null when unclustered); no other items-API change.
4. Config: `feed.cluster.{enabled,window-hours,title-threshold,wire-threshold,pass-delay-ms}` added to `application.yml` with pinned defaults above.

## Diff summary

Modified:
- `src/main/resources/application.yml` (feed.cluster defaults)
- `src/main/java/ca/feedline/config/FeedProperties.java` (Cluster nested config)
- `src/main/java/ca/feedline/dto/ItemDto.java` (+ clusterId)
- `src/main/java/ca/feedline/service/ItemQueryService.java` (clusterId via LEFT JOIN + optional member repo)
- `src/main/java/ca/feedline/web/ClustersController.java` (new)
- `src/test/java/ca/feedline/DockerFreeSmokeSliceTest.java` (`feed.cluster.enabled=false` for context-excluded slice)
- `src/test/java/ca/feedline/SchedulerInvokesPollerTest.java` (`feed.cluster.enabled=false` for context-excluded slice)

Created:
- `src/main/resources/db/migration/V14__story_clusters.sql`
- `src/main/java/ca/feedline/domain/StoryCluster.java`
- `src/main/java/ca/feedline/domain/StoryClusterMember.java`
- `src/main/java/ca/feedline/repository/StoryClusterRepository.java`
- `src/main/java/ca/feedline/repository/StoryClusterMemberRepository.java`
- `src/main/java/ca/feedline/service/StoryClusterService.java`
- `src/main/java/ca/feedline/service/TitleNormalizer.java`
- `src/main/java/ca/feedline/service/TitleTokenizer.java`
- `src/main/java/ca/feedline/service/StoryClusterMatcher.java`
- `src/main/java/ca/feedline/service/StoryClustering.java`
- `src/main/java/ca/feedline/dto/ClusterSummaryDto.java`
- `src/main/java/ca/feedline/dto/ClusterMemberDto.java`
- `src/main/java/ca/feedline/dto/ClusterDetailDto.java`
- `src/main/java/ca/feedline/web/ClustersController.java`
- `src/test/java/ca/feedline/service/StoryClusterMatchingTest.java`
- `src/test/java/ca/feedline/StoryClusterSeamTest.java`
- `REPORT-STORY-CLUSTERING.md`

## Tests mapped to required cases

`StoryClusterMatchingTest` (pure units): normalization drops stopwords/short tokens; case-insensitive Unicode; Jaccard edges incl. exactly 0.60 match under R1 and 0.50 just below; empty-set Jaccard; wire attribution within 120 chars and null beyond 120; alias delegate classes.

`StoryClusterSeamTest` (Testcontainers DB, scheduling disabled, pass invoked directly):
- same-source near-identical pair does NOT cluster
- R1 exactly 0.60 clusters; just below 0.60 does not
- R2 same-credit `(AP)` weak-headline match at exactly 0.40 (`matched_rule=R2`)
- R2 same-credit weak headline below threshold no match
- R2 different credit no match (weak headlines)
- window edge: 49h apart no cluster; 47h apart cluster
- three outlets one story -> one cluster of 3, representative earliest
- bridge merge into lower cluster id (5 members, lower id retained, higher cluster deleted)
- idempotency: second pass leaves cluster/member table counts identical
- API list shape/ordering (`memberCount` desc)
- API detail members ordered `(fetched_at, id)`; unknown id 404 problem+json
- unknown query param 400 problem+json; sinceHours=1000 accepted/clamped
- item DTO `clusterId` null before pass, set after pass

All new tests green; `mvn clean verify` reports 205 tests run (185 pre-existing + 20 new), failures 0, errors 0.

## Live-data proof

Constraint: this sandbox has a live PostgreSQL on port 5432 and a live `feedline` 8080 instance, but no `psql`/`pg_dump` client binary is installed. The live DB was opened read-only only; the equivalent of a pg_dump restore was performed by creating scratch DB `feedline_cluster_probe`, migrating it with the branch Flyway schema, copying live `feed_sources`/`items` into it via JDBC (41 sources, 4156 items), running the branch build on port 8081 against the scratch DB with `feed.cluster.pass-delay-ms=2000`, letting one clustering pass run, then reading `GET /api/v1/clusters?sinceHours=48`. Live `8080` instance and live `feedline` DB were never written. Sanctioned cleanup only: killed port 8081 and dropped `feedline_cluster_probe`.

`GET /api/v1/clusters?sinceHours=48` after one pass:
- total clusters: 131
- 5 examples (`id | memberCount | representativeTitle | sourceNames`):
  - 46 | 18 | Nvidia board reinstates Jensen Huang as CEO | [Al Jazeera English, CNBC Top News, CTV News, France 24 English, Le Monde English, Los Angeles Times, NPR, RTÉ News, Sydney Morning Herald, The Guardian, The Hindu, The Straits Times, The Verge, Times of India, Yahoo News Top Stories]
  - 5 | 6 | Audience evacuated from Don Toliver concert after unknown substance released in the crowd | [AP via Flipboard, Euronews, Global News, South China Morning Post, The Independent, The Straits Times]
  - 88 | 5 | L.A. Rep. Jimmy Gomez quitting after ethics panel finds evidence of sexual misconduct | [AP via Flipboard, Los Angeles Times, The Guardian, Yahoo News Top Stories]
  - 53 | 5 | China sentences ministry official to death for leaking over 100 state secrets | [AP via Flipboard, South China Morning Post, The Independent, Times of India]
  - 12 | 5 | Ukraine says it was behind drone attack that sank a Russian warship in Libya | [Anadolu Agency, France 24 English, RTÉ News, The Straits Times, Yahoo News Top Stories]
- Deliberate negative (two unrelated major stories in the same window NOT clustered): cluster 46 "Nvidia board reinstates Jensen Huang as CEO" and cluster 53 "China sentences ministry official to death for leaking over 100 state secrets" are distinct cluster ids; neither contains the other's items.

Cleanup: port 8081 stopped; `feedline_cluster_probe` dropped. No other deletions.

## Verify

`mvn clean verify`: BUILD SUCCESS.
- Surefire: `Tests run: 205, Failures: 0, Errors: 0, Skipped: 0`
- SpotBugs: `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`
- `git status` at branch tip: clean working tree.

## Push proof

Branch `story-clustering` pushed to origin fast-forward only (no force, no rebase, no dropped commits). No merge performed (HOLD).

## CLEANUP (names only; behavior frozen)

Removed (deleted files / methods):
- Deleted delegate classes entirely:
  - `src/main/java/ca/feedline/service/TitleNormalizer.java`
  - `src/main/java/ca/feedline/service/TitleTokenizer.java`
  - `src/main/java/ca/feedline/service/StoryClusterMatcher.java`
  - `src/main/java/ca/feedline/service/StoryClustering.java`
  Their callers (tests included) now use `StoryClusterService`'s canonical names directly.
- Deleted alias methods on `StoryClusterService`:
  - `normalize`, `tokens`, `titleTokens`, `tokenize` → canonical `normalizeTitle`
  - `jaccard` → canonical `jaccardSimilarity`
  - `wireAttribution`, `attribution` → canonical `wireCredit`
- Deleted redundant public pass entry points `runPass`, `runClusterPass`, `clusterPass`, `pass`, `doPass`. The `@Scheduled` entry point `scheduledPass()` now calls one private worker `performPass()` directly.

Canonical names kept on `StoryClusterService`:
- Pass entry point: `scheduledPass()` (@Scheduled) → private `performPass()`.
- Statics: `normalizeTitle(String)`, `jaccardSimilarity(Set, Set)`, `wireCredit(String)`.

Test impact:
- `StoryClusterMatchingTest`: all cases retained, calls updated to canonical names (`normalizeTitle`, `jaccardSimilarity`, `wireCredit`). Test names renamed to match canonical names.
- `aliasClassesDelegate` test in `StoryClusterMatchingTest` was REMOVED: it existed only to exercise the deleted alias/delegate classes, so it cannot compile after their deletion. Per the cleanup directive this is noted here rather than quietly keeping the count. Suite count therefore dropped from 205 to 204; no coverage case lost (the alias test covered no clustering behavior).
- `StoryClusterSeamTest`: `service.doPass()` → `service.scheduledPass()`; coverage list unchanged.

Verify of record:
- `mvn clean verify`: BUILD SUCCESS, clean working tree at new branch tip.
- Surefire: `Tests run: 204, Failures: 0, Errors: 0, Skipped: 0`
- SpotBugs: `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`

Push proof: fast-forward only to `origin/story-clustering`. HOLD — the merge walk follows grading of this pass.
