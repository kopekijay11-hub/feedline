# CI Diagnosis Report — Feedline

## Step 1: Extracted Evidence (from operator / run 43362274791 context)

**Run:** 43362274791 (commit 785bf7b), failed identically on 43395370434 (tip d3dbff4)  
**Step:** "Run full verification" (`mvn verify`), exit code 1, `maven-surefire-plugin:3.2.5:test`  
**Summary:** `Tests run: 5, Failures: 0, Errors: 2, Skipped: 0` (Spring test-context dump above summary — 2 context-load errors, not assertion failures)

### Erroring tests (2)
1. `ca.feedline.FeedPollerIntegrationTest.savesAndReadsBackItems`
2. `ca.feedline.FeedPollerIntegrationTest.duplicateSourceGuid_isInsertIfAbsent`

### Root cause (verbatim, as provided)
`UnsatisfiedDependencyException — feedPollerService constructor parameter 1 — No qualifying bean of type 'ca.feedline.service.FeedFetcher'`

*(Full per-test `<<< ERROR!` sections and full `Caused by:` chain for the first erroring test: not accessible in this environment; `gh` unavailable and unauthenticated run-log API access returns 403. Operator-provided root cause recorded above.)*

### Origin finding (verified, per operator instruction — do not re-derive)
In the committed code on origin, `ca.feedline.service.FeedFetcher` is a plain class with no Spring annotation; `config/FeedConfig.java` registers only the `feedProperties` bean; no `@Bean` registration for `FeedFetcher` exists anywhere on origin. This is the environment difference: the tower's green run had help the repo doesn't contain (stale compiled classes/target or local state not reflected in origin).

## Step 2: Environment difference (pending operator go)

**Working hypothesis:** CI fails to load Spring context because `FeedPollerService` requires a `FeedFetcher` bean but origin does not define/register one; tower was green due to local/stale build artifacts or uncommitted local changes not present in CI checkout.

**Evidence:** CI context-load failure `No qualifying bean of type 'ca.feedline.service.FeedFetcher'` for `feedPollerService` constructor parameter 1; origin has no `@Component`/`@Service` on `FeedFetcher` and no `@Bean` factory for it.

**Proposed smallest fix (pending go):** add the missing `@Bean` registration for `FeedFetcher` in `FeedConfig.java`. Before proposing/applying, also check `FeedNormalizer` (constructor parameter 2 of `FeedPollerService`) for the same gap and include only what is required. No CI/workflow changes; no test changes.

## Working tree state (Step 2.2, this session)
See assistant report for `git status --short`, `git diff --stat`, and relevant hunks.

## Outcome
Pending.
