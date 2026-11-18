# REPORT-FOLLOWUPS — M2a follow-ups (Job 1)

- Branch: `m2a-followups`
- Base: `origin/main` @ `0d334b5727e343fcba723620f88b9b603258c72c` (fetched at start of run)
- Docker availability (this session): **available** (`docker info` OK; Docker 29.1.3)
- Environment (pre-verify snapshot): `free -h` Mem total 14Gi, used 8.5Gi, free 3.3Gi, shared 1.4Gi, buff/cache 4.9Gi, available 6.5Gi, Swap used 7.1Gi; loadavg `2.00 1.72 1.55`.

## 1a. D2 stage-2 mutation proof (FeedNormalizer bean registration)

`FeedNormalizer` is registered only by `@Component` (`src/main/java/ca/feedline/service/FeedNormalizer.java:32`). Mutation performed:
1. Removed the `@Component` annotation from `FeedNormalizer`.
2. Ran `mvn -Dtest=ApplicationContextSmokeTest test`.

**Observed failure (expected):** context failed to start with unsatisfied dependency naming `FeedNormalizer`:
```
2026-11-18T02:18:33.251-05:00  WARN 1398056 --- [feedline] [           main] o.s.w.c.s.GenericWebApplicationContext   : Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'feedPollerService' defined in file [/home/joshua/feedline/target/classes/ca/feedline/service/FeedPollerService.class]: Unsatisfied dependency expressed through constructor parameter 2: No qualifying bean of type 'ca.feedline.service.FeedNormalizer' available: expected at least 1 bean which qualifies as autowire candidate. Dependency annotations: {}
Parameter 2 of constructor in ca.feedline.service.FeedPollerService required a bean of type 'ca.feedline.service.FeedNormalizer' that could not be found.
Caused by: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'feedPollerService' defined in file [.../FeedPollerService.class]: Unsatisfied dependency expressed through constructor parameter 2: No qualifying bean of type 'ca.feedline.service.FeedNormalizer' available: expected at least 1 bean which qualifies as autowire candidate. Dependency annotations: {}
Caused by: org.springframework.beans.factory.NoSuchBeanDefinitionException: No qualifying bean of type 'ca.feedline.service.FeedNormalizer' available: expected at least 1 bean which qualifies as autowire candidate. Dependency annotations: {}
```
(Full log captured in-session as `/tmp/mutation-fail.log`; test run reported `Tests run: 1, Failures: 0, Errors: 1` / `BUILD FAILURE` for that targeted mutation run.)

3. Restored file exactly (`git checkout -- src/main/java/ca/feedline/service/FeedNormalizer.java`; `@Component` confirmed present).
4. Re-ran `mvn -Dtest=ApplicationContextSmokeTest test`: **BUILD SUCCESS**, `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0` (Docker/Testcontainers PostgreSQL used).

**Result:** mutation proof **PASS** (bean absence breaks context; restoration green). Docker was available, so this was executed (not marked NOT RUN).

## 1b. SpotBugs hygiene

Findings regenerated with `mvn spotbugs:spotbugs` (baseline before fixes on this branch): **17** BugInstances (`target/spotbugsXml.xml`).

### Disposition (every finding)

| # | Type | Priority | Class / method (line) | Disposition | Rationale / fix |
|---|------|----------|------------------------|-------------|-----------------|
| 1 | `DM_CONVERT_CASE` | 3 | `RobotsTxtParser.parse` (156) | **FIXED** | Replaced `toLowerCase()` with `toLowerCase(Locale.ROOT)` (added `java.util.Locale` import) to make directive parsing locale-independent. |
| 2 | `UCF_USELESS_CONTROL_FLOW` | 3 | `FeedPollerService.pollOnce` (98) | **FIXED** | Removed genuinely dead/useless control flow: `if (saved == 0) { /* Check if it was a 304 - track separately */ }` did nothing, and the accompanying unused `skippedBy304` local was removed. Poll counting behavior unchanged. |
| 3 | `SF_SWITCH_NO_DEFAULT` | 2 | `RobotsTxtParser.parse` (159) | **FIXED** | Added a `default` branch (no-op `break`) so unrecognized robots directives are safely ignored without falling through SpotBugs' missing-default rule. |
| 4 | `EI_EXPOSE_REP` | 2 | `FeedProperties.getSources` (67) | **EXCLUDED** (`spotbugs-exclude.xml`) | Spring `@ConfigurationProperties` bind target; returned list is part of framework-managed mutable binding surface—defensive copy would break binding semantics. |
| 5 | `EI_EXPOSE_REP2` | 2 | `FeedProperties.setSources` (71) | **EXCLUDED** (`spotbugs-exclude.xml`) | Same as #4: binder sets the collection by reference intentionally. |
| 6 | `EI_EXPOSE_REP` | 2 | `Item.getSource` (77) | **EXCLUDED** (`spotbugs-exclude.xml`) | JPA entity association exposed intentionally for ORM navigation/persistence; copying would break entity identity/management semantics. |
| 7 | `EI_EXPOSE_REP2` | 2 | `Item.<init>` (59) | **EXCLUDED** (`spotbugs-exclude.xml`) | JPA entity holds managed `FeedSource` reference by design. |
| 8 | `EI_EXPOSE_REP2` | 2 | `Item.setSource` (81) | **EXCLUDED** (`spotbugs-exclude.xml`) | JPA entity setter for managed association; external mutable ownership is inherent to ORM model. |
| 9 | `EI_EXPOSE_REP2` | 2 | `FeedFetcher.<init>` (60) | **EXCLUDED** (`spotbugs-exclude.xml`) | Constructor-held injected Spring/service collaborator (`RobotsTxtParser`), not a defensively-copied value object. |
| 10 | `EI_EXPOSE_REP2` | 2 | `FeedPollerService.<init>` (61) | **EXCLUDED** (`spotbugs-exclude.xml`) | Constructor-held injected dependency (`FeedProperties` config/collaborators), not a value object to copy. |
| 11 | `EI_EXPOSE_REP2` | 2 | `FeedPollerService.<init>` (62) | **EXCLUDED** (`spotbugs-exclude.xml`) | Constructor-held injected service collaborator (`FeedFetcher`). |
| 12 | `EI_EXPOSE_REP2` | 2 | `RobotsTxtParser$RobotsRules.<init>` (196) | **EXCLUDED** (`spotbugs-exclude.xml`) | Internal immutable-by-convention holder constructed from a freshly parsed list within the parser; no external mutable ownership exposed. |
| 13 | `REC_CATCH_EXCEPTION` | 3 | `FeedFetcher.fetchWithRetry` (211) | **EXCLUDED** (`spotbugs-exclude.xml`) | Deliberate broad catch at network/fetch boundary to convert failures into controlled retry/skip behavior and keep polling resilient. |
| 14 | `REC_CATCH_EXCEPTION` | 3 | `FeedPollerService.extractNameFromUrl` (234) | **EXCLUDED** (`spotbugs-exclude.xml`) | Deliberate broad catch at parse/fallback boundary (URL parsing/name derivation) to degrade gracefully to a safe fallback name. |
| 15 | `REC_CATCH_EXCEPTION` | 3 | `FeedPollerService.parse` (256) | **EXCLUDED** (`spotbugs-exclude.xml`) | Deliberate broad catch at feed-parse boundary; failures must not poison the poll cycle and validators must remain untouched on parse failure. |
| 16 | `REC_CATCH_EXCEPTION` | 3 | `RobotsTxtParser.fetchRobots` (109) | **EXCLUDED** (`spotbugs-exclude.xml`) | Deliberate broad catch at network boundary; robots fetch errors treated as non-cacheable/disallowed-for-poll per existing policy. |
| 17 | `REC_CATCH_EXCEPTION` | 3 | `RobotsTxtParser.fetchRobotsContent` (125) | **EXCLUDED** (`spotbugs-exclude.xml`) | Same boundary rationale as #16 (content fetch). |

### Wiring / end state
- Added `spotbugs-exclude.xml` at repo root with per-class/method rationale-aligned suppressions.
- Wired into `pom.xml` SpotBugs configuration via `<excludeFilterFile>${project.basedir}/spotbugs-exclude.xml</excludeFilterFile>` (execution/phase unchanged: `spotbugs:check` bound to `verify`, `failOnError=false`).
- Post-fix `mvn spotbugs:spotbugs`: **0 BugInstances** in both `target/spotbugsXml.xml` and `target/spotbugs.xml`.
- `mvn verify` (Docker available in-session, one heavy process at a time): **BUILD SUCCESS**. SpotBugs check reported `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`.
- Tests during verify: **Tests run: 25, Failures: 0, Errors: 0, Skipped: 0** (Surefire, includes Testcontainers-backed integration/smoke tests) plus failsafe integration-test/verify phases completed. Expected error-log lines from negative-path tests (e.g. malformed/XXE fixtures, intentional duplicate-key constraint violations) were observed in output but all tests passed.

## 1c. STRETCH — Docker-free sliced context smoke test

**Abandoned (not implemented).** The application’s bean graph is tightly coupled to JPA/DataSource/Flyway/Testcontainers-backed infrastructure; producing a genuinely sliced Spring-context smoke variant that coexists with the existing `@SpringBootTest` + Testcontainers `ApplicationContextSmokeTest` and proves the bean graph constructs with repositories/datasource mocked could not be made green quickly without risk of a half-built/red artifact. Per instructions, no half-built stretch item was left behind.

## Test evidence & environment
- Docker availability during session: **available** (and used for 1a mutation proof and full `mvn verify`).
- Commands run (representative): `mvn spotbugs:spotbugs`, `mvn -DskipTests test-compile`, `mvn -Dtest=ApplicationContextSmokeTest test` (mutation fail then restored green), `mvn verify`.
- Environment at end of Job 1 work: `free -h` Mem total 14Gi, used 8.8Gi, free 2.9Gi, shared 1.5Gi, buff/cache 4.9Gi, available 6.2Gi, Swap used 7.1Gi; loadavg `3.88 2.27 1.74`; `docker-ok`.
