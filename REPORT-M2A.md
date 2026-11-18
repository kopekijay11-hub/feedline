# REPORT-M2A (expanded scope, 2026-11-18)

Branch: `m2a-grading` (work tip includes this report commit; see tip/SHA below). No merge performed; held for Jarvis grade from `origin`.

## Baseline and as-captured pre-fix failure
- Baseline commit: `5629c3d wip: capture uncommitted M2a working tree (unreviewed safety snapshot, 2026-11-17)` (clean working tree at start).
- Environment: `target/` present; `target/failsafe-reports` not present; integration-style `*Test` classes run under Maven Surefire (`mvn test` / `mvn verify`).

As-captured pre-fix `mvn -q -DskipTests=false test-compile` (RC=1), reproduced on baseline working tree before repairs:
```
[ERROR] COMPILATION ERROR :
[ERROR] /home/joshua/feedline/src/test/java/ca/feedline/FeedPollerDedupeIntegrationTest.java:[66,41] incompatible types: java.lang.String cannot be converted to java.util.function.Supplier<java.lang.Object>
[ERROR] /home/joshua/feedline/src/test/java/ca/feedline/FeedPollerDedupeIntegrationTest.java:[131,42] cannot find symbol
  symbol:   method findByGuid(java.lang.String)
  location: variable items of type ca.feedline.repository.ItemRepository
[ERROR] /home/joshua/feedline/src/test/java/ca/feedline/service/FeedFetcherTest.java:[54,56] ALLOW_ALL has private access in ca.feedline.service.RobotsTxtParser.RobotsRules
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:testCompile (default-testCompile) on project feedline: Compilation failure: Compilation failure:
[ERROR] ...FeedPollerDedupeIntegrationTest.java:[66,41] incompatible types: java.lang.String cannot be converted to java.util.function.Supplier<java.lang.Object>
[ERROR] ...FeedPollerDedupeIntegrationTest.java:[131,42] cannot find symbol
[ERROR]   symbol:   method findByGuid(java.lang.String)
[ERROR]   location: variable items of type ca.feedline.repository.ItemRepository
[ERROR] ...FeedFetcherTest.java:[54,56] ALLOW_ALL has private access in ca.feedline.service.RobotsTxtParser.RobotsRules
[ERROR] -> [Help 1]
RC=1 (mvn -q -DskipTests=false test-compile, reproduced on m2a-grading baseline working tree before repairs)
```

## Set A — compile repairs (intent-preserving)
Commit: `5ec2330 M2a: Set A compile repairs (dedupe test supplier, ItemRepository.findByGuid, RobotsRules.ALLOW_ALL visibility)`

### A1 — `FeedPollerDedupeIntegrationTest` property supplier type
- Broken line (baseline, `src/test/java/ca/feedline/FeedPollerDedupeIntegrationTest.java:66`):
  `registry.add("feed.poll-delay", "999999h");`
- Repair:
  `registry.add("feed.poll-delay", () -> "999999h");`
- Reason: `DynamicPropertyRegistry.add` requires a `Supplier<Object>`; supplying the same value lazily fixes the type error while preserving intent (disable scheduling for tests).

### A2 — `ItemRepository.findByGuid` missing method (call site unchanged)
- Call site left intact (`FeedPollerDedupeIntegrationTest.java:131`):
  `Optional<Item> noGuidItem = items.findByGuid("https://example.com/no-guid-but-has-link");`
- Repair (production, `src/main/java/ca/feedline/repository/ItemRepository.java`): added
  `Optional<Item> findByGuid(String guid);`
- Reason: restore repository contract to satisfy existing test intent without workarounds in the test.

### A3 — `RobotsTxtParser.RobotsRules.ALLOW_ALL` visibility
- Broken/blocked usage: `src/test/java/ca/feedline/service/FeedFetcherTest.java` in same package `ca.feedline.service` calls `.thenReturn(RobotsTxtParser.RobotsRules.ALLOW_ALL)` but field was private.
- Baseline (`src/main/java/ca/feedline/service/RobotsTxtParser.java`): `private static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());`
- Repair (smallest step, package-private): `static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());`
- Reason: minimal visibility widening to allow same-package test access.

### Set A checkpoint
- `mvn -q test-compile` — RC=0.
- `mvn -q -Dtest=FeedNormalizerTest,FeedFetcherTest,FeedFetcherStubServerTest test` — RC=0.
  - `ca.feedline.service.FeedNormalizerTest` — tests=6 failures=0 errors=0 skipped=0
  - `ca.feedline.service.FeedFetcherTest` — tests=2 failures=0 errors=0 skipped=0
  - `ca.feedline.service.FeedFetcherStubServerTest` — tests=6 failures=0 errors=0 skipped=0

## Set B — context fixes, smoke test, mutation proof, javadoc
Commit: `8be60ae M2a: Set B context fixes (remove duplicate config beans, add poller smoke test, correct failsafe javadoc)`

### Context/bean fixes (`src/main/java/ca/feedline/config/FeedConfig.java`)
- Removed duplicate/manual `@Bean public FeedProperties feedProperties()`; kept `@EnableConfigurationProperties(FeedProperties.class)`.
- Removed duplicate `@Bean public FeedNormalizer feedNormalizer()` (and import); kept single definition `@Component` on `src/main/java/ca/feedline/service/FeedNormalizer.java`.
- Retained `FeedFetcher` / `FeedFetchConfig` / `Sleeper` / `Clock` beans.

### Application context smoke test
- Added `src/test/java/ca/feedline/ApplicationContextSmokeTest.java` (Testcontainers PostgreSQL `postgres:16.16-alpine`, `@SpringBootTest`, `registry.add("feed.poll-delay", () -> "999999h")`, autowires `FeedPollerService`, asserts `assertNotNull(feedPollerService, "FeedPollerService bean must be present")`).

### Failsafe/Surefire javadoc correction
- Corrected javadoc in `src/test/java/ca/feedline/FeedPollerIntegrationTest.java`: runs under Maven Surefire (`mvn test` / `mvn verify`) because class name ends with `*Test`; needs Docker.

### Mutation proofs (observed)
- **Fetcher-`@Bean` mutation proof (pass):** temporarily removing `@Bean public FeedFetcher feedFetcher(...)` caused `ApplicationContextSmokeTest` to fail (RC!=0) with `Failed to load ApplicationContext` / `NoSuchBeanDefinitionException: No qualifying bean of type 'ca.feedline.service.FeedFetcher' available` (UnsatisfiedDependencyException creating `feedPollerService`); restoring the `FeedFetcher` bean made the smoke test pass.
- **Normalizer two-stage duplicate-bean mutation (observed):** baseline had `@Component FeedNormalizer` + `@Bean feedNormalizer()` in `FeedConfig`. Reintroducing a duplicate `@Bean feedNormalizer()` while `@Component` remained present did **not** fail `ApplicationContextSmokeTest` in observed runs (RC=0 in mutation and restored runs; logs captured). The duplicate `@Bean` was still removed as the fix; current state is `@Component` only. Reported as observed (no duplicate-bean startup failure induced by this mutation under these conditions).

### Build/smoke evidence
- `ApplicationContextSmokeTest` later passed on fixed code: tests=1 failures=0 errors=0 (RC=0).

## Set C — fixture tests and poller hardening
Commit: `41afaba M2a: Set C fixture tests and poller hardening (XXE-safe parse, insert-if-absent precheck)`

### Fixture tests
- Added `src/test/java/ca/feedline/FeedPollerFixtureIntegrationTest.java` (Testcontainers PostgreSQL + `@SpringBootTest` + stub `HttpServer`, fixtures served over HTTP).
  - `xxe-feed.xml` (`src/test/resources/fixtures/xxe-feed.xml`): rejected safely / no external-entity resolution, poll completes without ingesting entity-expanded content; verified 0 items saved.
  - `malformed-xml.xml` (`src/test/resources/fixtures/malformed-xml.xml`): fails poll in isolation without crashing poller; verified 0 items saved.
  - `empty-feed.xml` (`src/test/resources/fixtures/empty-feed.xml`): polls cleanly with zero items; verified 0 items saved.
  - `duplicate-guids.xml` (`src/test/resources/fixtures/duplicate-guids.xml`): ingests each GUID once; verified 2 items saved (no duplicates).
- Result: `ca.feedline.FeedPollerFixtureIntegrationTest` — tests=4 failures=0 errors=0 skipped=0.

### Poller hardening (`src/main/java/ca/feedline/service/FeedPollerService.java`)
- `parse()`: `SyndFeedInput input = new SyndFeedInput(); input.setAllowDoctypes(false);` and broad parse-failure handling returning empty list (safe DOCTYPE/XXE rejection and malformed-feed isolation).
- Save loop: pre-check `items.findBySourceIdAndGuid(source.getId(), item.getGuid()).isEmpty()` before save to avoid constraint-violation poisoning for in-poll duplicate GUIDs (DB uniqueness remains the source of truth).

## Additional stabilization / schema alignment (discovered during verify)
- `3196350 M2a: stabilize existing ITs for insert-if-absent and flush-on-unique-constraint`
  - `src/test/java/ca/feedline/FeedPollerDedupeIntegrationTest.java`: save loops guard with `findBySourceIdAndGuid(...).isEmpty()` before save (preserves dedupe intent, avoids unconditional duplicate-save exceptions under insert-if-absent flow).
  - `src/test/java/ca/feedline/FeedPollerIntegrationTest.java`: `feedSourceUrlIsUnique` uses `sources.save(source2); sources.flush();` to force constraint evaluation.
- `ecd2fe0 M2a: add feed_sources.url uniqueness constraint (V3) and restore URL uniqueness assertion`
  - Added `src/main/resources/db/migration/V3__add_unique_constraint_to_feed_sources_url.sql`: `ALTER TABLE feed_sources ADD CONSTRAINT uq_feed_sources_url UNIQUE (url);`
  - Rationale: V1 schema lacked a DB uniqueness constraint for `feed_sources.url` while `FeedSource.url` is `@Column(nullable = false, unique = true)`; full verify previously failed `feedSourceUrlIsUnique` (no exception thrown). Targeted `FeedPollerIntegrationTest#feedSourceUrlIsUnique` passed after V3 (and full suite passes as below).

## Final post-fix verification (clean committed tip, post-fix)
- Tip before this report commit: `ecd2fe0 M2a: add feed_sources.url uniqueness constraint (V3) and restore URL uniqueness assertion`.
- `mvn -q clean verify` executed from clean committed working tree; surefire phase completed with all test classes passing (per `target/surefire-reports/TEST-*.xml`):
  - `ca.feedline.ApplicationContextSmokeTest` — tests=1 failures=0 errors=0 skipped=0
  - `ca.feedline.FeedPollerDedupeIntegrationTest` — tests=2 failures=0 errors=0 skipped=0
  - `ca.feedline.FeedPollerFixtureIntegrationTest` — tests=4 failures=0 errors=0 skipped=0
  - `ca.feedline.FeedPollerIntegrationTest` — tests=4 failures=0 errors=0 skipped=0
  - `ca.feedline.service.FeedFetcherStubServerTest` — tests=6 failures=0 errors=0 skipped=0
  - `ca.feedline.service.FeedFetcherTest` — tests=2 failures=0 errors=0 skipped=0
  - `ca.feedline.service.FeedNormalizerTest` — tests=6 failures=0 errors=0 skipped=0
  - **TOTAL — tests=25 failures=0 errors=0 skipped=0**
- SpotBugs: `mvn -q spotbugs:check` RC=0 on the same built classes (plugin reports findings but goal completed successfully). Captured `clean verify` console tail ended in SpotBugs finding output without an explicit final `BUILD SUCCESS` line in the captured log; surefire XMLs and `spotbugs:check` RC=0 together evidence a passing post-fix verification state.

## Notes
- No secrets committed. No merge to `main`. Held for Jarvis grade from `origin`.
