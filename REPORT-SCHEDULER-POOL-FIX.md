# Scheduler Starvation Fix — Report

## Step 0: base and premise check

- `git fetch origin --prune` completed. `git rev-parse origin/main` and
  `git ls-remote origin refs/heads/main` both returned
  `d079493c58ad05c80d73d570f8d56e06f7b1e059` (the expected base).
- Branch `scheduler-pool-fix` was cut from that verified base SHA. At verification
  time, `git merge-base HEAD origin/main` equalled
  `d079493c58ad05c80d73d570f8d56e06f7b1e059`.
- Premise greps for `TaskScheduler`, `ThreadPoolTaskScheduler`,
  `SchedulingConfigurer`, and literal `spring.task.scheduling` found no matches
  (`git grep` exit 1). Caveat recorded and accepted by the operator before code
  changes: `src/main/resources/application.yml` contains nested
  `spring: task: scheduling: shutdown:` keys (flattened
  `spring.task.scheduling.shutdown.await-termination=true` and
  `await-termination-period=30s`). They configure only scheduler shutdown, not a
  bean, pool size, or thread-name prefix, so the single-thread starvation
  diagnosis stands.
- No test delay or health threshold was changed.

## Change 1: scheduler thread pool

`SchedulingConfig` now declares the application's only `TaskScheduler` bean.

- Class: `org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler`
- Property: `feed.scheduling.pool-size`
- Default pool size: `4`
- Thread name prefix: `feedline-sched-`
- The bean is returned as `ThreadPoolTaskScheduler`, so Spring Boot's scheduler
  auto-configuration backs off and all `@Scheduled` methods use this pool.
- No task delay or interval value was changed.

## Change 2: clustering pass observability

`StoryClusterService.scheduledPass()` now emits exactly two INFO lines per pass:

- at start: `Story cluster pass started`
- at completion: `Story cluster pass completed in {} ms; items considered: {}`

`performPass()` returns the count of items it loaded; disabled/unwired passes
return `0`. The pass previously computed no aggregate items-assigned or
clusters-created/merged counters, so, as the work order allows, the completion
line logs duration plus `items considered` only. Matching, transactions, and
the pass's `fixedDelayString` scheduling were not changed.

Real example from `mvn -B -Dtest=SchedulingConfigTest,StoryClusterPassLoggingTest test`
(2026-11-25 00:16:19 EST):

```
00:16:19.679 [main] INFO ca.feedline.service.StoryClusterService -- Story cluster pass started
00:16:19.689 [main] INFO ca.feedline.service.StoryClusterService -- Story cluster pass completed in 6 ms; items considered: 2
```

## Test evidence

- `SchedulingConfigTest.providesSingleThreadPoolTaskSchedulerWithDefaultPoolAndPrefix`
  (Docker-free `ApplicationContextRunner`): asserts one `TaskScheduler` bean, one
  `ThreadPoolTaskScheduler` bean, `getScheduledThreadPoolExecutor().getCorePoolSize() == 4`,
  and `getThreadNamePrefix() == "feedline-sched-"`. Result: `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
  The assertion is concrete: a single-thread default or wrong prefix fails it.
- `StoryClusterPassLoggingTest.scheduledPassLogsStartAndCompletionWithDuration`
  (unit test with Mockito repositories and a Logback `ListAppender` already on the
  classpath; no new dependency): asserts the two log lines and that completion
  carries a millisecond duration and `items considered: 2`. Result:
  `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
- Full suite: base had 213 tests; this branch has 215. No existing test was
  weakened, deleted, or re-pinned to pass.

## Verification of record

Command: `mvn -B verify`, run on a clean working tree at the committed code+tests
tip. `git status --short` was empty immediately before and after the run.

Surefire verdict line, verbatim:

```
[INFO] Tests run: 215, Failures: 0, Errors: 0, Skipped: 0
```

SpotBugs verdict lines, verbatim:

```
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] No errors/warnings found
```

Gate verdict line, verbatim:

```
[INFO] BUILD SUCCESS
```

## Scope statement

No health threshold, poll delay, or clustering behaviour was changed.

## Disposition

Push: fast-forward only as `scheduler-pool-fix`. HOLD: no merge, no live
before/after observation, and no scorecard work performed.

## Completion pass

- `SchedulingConfig.taskScheduler(...)` now sets
  `scheduler.setWaitForTasksToCompleteOnShutdown(true)` and
  `scheduler.setAwaitTerminationSeconds(30)` on the explicit
  `ThreadPoolTaskScheduler` bean.
- `SchedulingConfigTest.waitsForInFlightTasksToFinishOnShutdown` submits a
  ~1500 ms sleeping `Runnable`, waits for it to start, calls
  `scheduler.shutdown()`, then asserts `completedNormally` is true and
  `interrupted` is false. The test discriminates: with the two setters
  temporarily removed, the default `waitForTasksToCompleteOnShutdown=false`
  makes shutdown interrupt the sleeper and the test fails; with them present
  it passes. No reflection is used.
- The 30-second await-termination value is not behaviourally testable without
  a 30 s test; it is covered by the settings statement above only.
- Re-verification on a clean working tree at the committed code+tests tip:
  `git status --short` was empty before and after `mvn -B verify`; the added
  test method makes the suite 216 tests.

Surefire verdict line, verbatim:

```
[INFO] Tests run: 216, Failures: 0, Errors: 0, Skipped: 0
```

SpotBugs verdict lines, verbatim:

```
[INFO] BugInstance size is 0
[INFO] Error size is 0
[INFO] No errors/warnings found
```

Gate verdict line, verbatim:

```
[INFO] BUILD SUCCESS
```
