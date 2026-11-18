# REPORT-CI — Feedline CI episode record

Date: 2026-11-17  
Branch: `main`  
Fix commit (referenced): `ebf64e7` — "fix: register FeedFetcher bean in FeedConfig (fixes CI context failure)"  
Safety-capture branch tip (referenced): `5629c3d5` on `wip/m2a-uncommitted-2026-11-17`

## 1. Step 1 evidence (CI run 43362274791)

Failing CI run: **43362274791** (Feedline CI, main, push).

Two erroring tests:
- `FeedPollerIntegrationTest.savesAndReadsBackItems`
- `FeedPollerIntegrationTest.duplicateSourceGuid_isInsertIfAbsent`

Root-cause chain (from that run):
- `UnsatisfiedDependencyException` — `feedPollerService` constructor parameter 1 — no qualifying bean of type `ca.feedline.service.FeedFetcher`

## 2. Root cause

The committed M1 code never registered a `FeedFetcher` bean. `FeedFetcher` is a plain class and `FeedConfig` registered only `feedProperties`. CI was the first environment to run only the committed code.

## 3. Tower mechanism

The tower's green runs rested on its working tree, which held a substantial uncommitted M2a build whose `FeedConfig` registers the fetcher bean.

`tower divergence was at M2a scale, not a stray registration.`

## 4. Option A sequence (as executed)

1. **Safety capture:** preserved the uncommitted tower state to `wip/m2a-uncommitted-2026-11-17` (tip `5629c3d5`).
2. **One-bean fix on clean `main`:** registered `FeedFetcher` in `FeedConfig` in commit `ebf64e7` ("fix: register FeedFetcher bean in FeedConfig (fixes CI context failure)").
3. **Verifications:**
   - Operator's tower `mvn clean verify` on `main` @ `ebf64e7`: **BUILD SUCCESS** (finished 2026-11-17 23:39 EST).
   - CI run **44238318507** (Feedline CI, `main`, push, commit `ebf64e7`): **completed success**.

## 5. Closing note

M2a is graded as a separate job from the wip branch; nothing in this report endorses that work.
