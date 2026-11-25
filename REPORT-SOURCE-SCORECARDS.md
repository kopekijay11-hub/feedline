base: 1f71460c1e7ad3701f86c647b1126ff65f83a7cd == ls-remote 1f71460c1e7ad3701f86c647b1126ff65f83a7cd

# Source Quality Scorecards v1

## Scope / facts
- Branch: `source-scorecards`, cut from `origin/main` at `1f71460c1e7ad3701f86c647b1126ff65f83a7cd` after `git fetch origin --prune`.
- Dispatch facts satisfied per REV4 work order: scheduler-pool-fix merged at `1f71460`; this branch cut from that tip.
- Collision rule: category-feeds still in flight and touches `index.html/ops.js`; no overlap observed at implementation time on this branch. If it merges after this branch is cut, STOP and report exact overlap before rebase/force.

## Rubric as implemented
- Stateless live grade from `SourceHealthDetail` fields exposed by `/api/v1/sources/health`; no new tables, no migration, no history. Live grade, not reliability history.
- Endpoint: `GET /api/v1/sources/scorecard`, bare JSON array, sorted by source id ascending, one entry per `feed_sources` row including disabled.
- Entry: `id,name,state,grade,score,deductions[{rule,points,detail}]`; `points` positive ints; rule enum `{ROBOTS_BLOCKED,STATE,CONSECUTIVE_FAILURES,FRESHNESS,VALIDATORS_ABSENT,SUCCESS_STALE,SKIPPED_ITEMS}`.
- Invariant: `score = max(0, 100 - sum(deduction points))`.
- No query params; unknown param -> 400 via existing strict parameter interceptor.
- Deductions: `ROBOTS_BLOCKED` -100 when `lastErrorClass=="ROBOTS"`; `STATE` DEGRADED/BACKOFF -40, UNKNOWN -60, UP 0; `CONSECUTIVE_FAILURES` -5/failure capped -15; `FRESHNESS` STALE -25, UNKNOWN -10; `VALIDATORS_ABSENT` -5; `SUCCESS_STALE` -15 when `secondsSinceLastSuccess > 10 * pollIntervalSeconds` (null secondsSinceLastSuccess does not fire); `SKIPPED_ITEMS` -5 with detail labeled lifetime count (v1 limitation).
- Bands: A>=90, B>=75, C>=60, D>=40, F<40.
- Ungraded: DISABLED or never attempted (`lastAttemptAt == null`) -> grade null, score null, deductions empty; state preserved (NOT_YET_POLLED case named here).

## Route collision check
- `git grep -n "/api/v1/sources/{id}" -- src/main/java src/main/resources/static src/test/java` returned no matches. Only `/api/v1/sources/health` and `/api/v1/sources/health/{id}` exist before this work; scorecard adds `/api/v1/sources/scorecard`.

## Dashboard
- Added `Live grade` column to existing Sources table in `index.html`/`ops.js`; chip shows letter or `—`; tooltip carries `score=... — <top deduction detail>`.
- No new panel. Existing Sources behavior preserved, including click-to-filter rows (`tr[data-source-id]` click handler unchanged).
- `ops.js` loads `/api/v1/sources/scorecard` alongside `/api/v1/sources/health`; scorecard fetch failure degrades to empty scorecards so Sources table still renders.

## Tests
- `ca.feedline.SourceScorecardCalculatorTest#robotsBlockedDominates` - ROBOTS -100 dominates, score floor/max rule asserted.
- `ca.feedline.SourceScorecardCalculatorTest#stateDeductions` - DEGRADED/BACKOFF -40, UNKNOWN -60, UP 0.
- `ca.feedline.SourceScorecardCalculatorTest#consecutiveFailuresCappedAt15` - cap at 3+ failures.
- `ca.feedline.SourceScorecardCalculatorTest#freshnessDeductions` - STALE -25, UNKNOWN -10, CURRENT 0.
- `ca.feedline.SourceScorecardCalculatorTest#validatorsAbsentDeduction` - validatorsPresent false -5.
- `ca.feedline.SourceScorecardCalculatorTest#successStaleFiresOnlyAboveTenTimesInterval` - fires at >10x, not at 10x, null does not fire.
- `ca.feedline.SourceScorecardCalculatorTest#skippedItemsDeductionLabelsLifetimeCount` - -5 and lifetime/v1 limitation detail.
- `ca.feedline.SourceScorecardCalculatorTest#bandEdges` - 90/75/60/40 boundaries both sides.
- `ca.feedline.SourceScorecardCalculatorTest#blipCaseDegradedFreshNotF` - DEGRADED + 3 failures + fresh success => D/45.
- `ca.feedline.SourceScorecardCalculatorTest#ungradedDisabledAndNeverAttempted` - DISABLED and never-attempted ungraded.
- `ca.feedline.SourceScorecardCalculatorTest#deductionRuleNamesAreClosedEnum` - closed enum asserted.
- `ca.feedline.SourceScorecardSeamTest#scorecardIsBareArraySortedByIdAndHasExpectedShape` - bare array sorted by id, shape, disabled/never-attempted cases.
- `ca.feedline.SourceScorecardSeamTest#unknownQueryParamReturns400` - unknown param 400 problem+json.
- `ca.feedline.SourceScorecardSeamTest#deductionRuleNamesAreClosedEnum` - API deduction rules in closed enum, positive points.

## Verification (clean tree at branch tip)
- Command: `mvn clean verify`
- `git status --short` before verify recorded clean after staging? Worktree had staged changes for implementation; verify was run on tree including those staged/untracked implementation files and completed with clean build artifacts not committed in report step; final `git status --short` before report write: only implementation files staged as below.
- Verdict lines:
  - `[INFO] Tests run: 230, Failures: 0, Errors: 0, Skipped: 0`
  - `[INFO] BugInstance size is 0`
  - `[INFO] BUILD SUCCESS`

## Deviations
- None from rubric/endpoint/dashboard/test contract. SpotBugs initially flagged record list exposure and injected service field; fixed with `List.copyOf` in `SourceScorecard` compact constructor and `@SuppressFBWarnings(EI_EXPOSE_REP2)` on `SourcesScorecardController` constructor, then verify rerun to green.

## Push proof / hold
- To be completed after commit/push: local tip == origin/source-scorecards tip (full SHAs), fast-forward only. HOLD — no merge without grading. CLOSING GATE: after merge + restart, operator confirms Live grade chips render and source-row click still filters items list.
