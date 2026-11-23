# REPORT-SMOKE-SLICE — Docker-free smoke slice + soft-404 content-sanity

Branch: `smoke-slice` (base `main @ 25016b1`)

## STEP 0 — Survey

Docker is forced at context boot by every `@Testcontainers` + `PostgreSQLContainer` (and, for Kafka paths, `KafkaContainer`) test. The full list is 19 test classes, including `ApplicationContextSmokeTest`, `ItemsApiSeamTest`, `OpsDashboardSeamTest`, `FeedPollerIntegrationTest`, `OutboxRelay*`, `*IntegrationTest`, and the `*SeamTest` classes that boot the real bean graph against PostgreSQL 16.16 (and Kafka 7.8.0 where the outbox is exercised).

Beans that require a live DB/Kafka at boot: all four Spring Data JPA repositories (`FeedSourceRepository`, `ItemRepository`, `OutboxEventRepository`, `IngestSkipRepository`) plus `SourceRegistryInitializer` (ApplicationRunner that seeds `feed_sources`), Flyway migration runner, and `OutboxRelayService`/`OutboxCleanupService` (Kafka producer + `@Transactional` cleanup). Outbox is disabled in the smoke slice via `feed.outbox.enabled=false` / `feed.outbox.cleanup.enabled=false`.

Mechanism for Docker-free boot: exclude `DataSourceAutoConfiguration`, `HibernateJpaAutoConfiguration`, and `FlywayAutoConfiguration`, and `@MockBean` the four repositories plus `ItemIngestService`, `IngestSkipRecorder`, and `ItemQueryService`; supply a stub `PlatformTransactionManager` via `@TestConfiguration`. This mirrors the existing `SchedulerInvokesPollerTest` pattern. No Docker/Testcontainers involvement; `DOCKER_HOST` can be pointed at a nonexistent socket and the test still passes.

## Part 1 — Docker-free smoke slice

New test: `ca.feedline.DockerFreeSmokeSliceTest`.

- (a) Context boots with no Docker available (see push-proof run below).
- (b) `GET /actuator/health` returns 200 via MockMvc; `FeedPollerService` bean asserted non-null.
- (c) Mutation property: removing the `FeedNormalizer` bean registration (`@Bean feedNormalizer` in `FeedConfig`) makes the test fail with `UnsatisfiedDependencyException: No qualifying bean of type 'ca.feedline.service.FeedNormalizer'`. Observed on a deliberate run, then reverted (`git checkout -- src/main/java/ca/feedline/config/FeedConfig.java`).

Docker-absence evidence (real, not a flag): run with `DOCKER_HOST=unix:///tmp/definitely-not-a-docker-socket`:

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.44 s -- in ca.feedline.DockerFreeSmokeSliceTest
BUILD SUCCESS
```

No Testcontainers/Docker lines appear in that run.

## Part 2 — Soft-404 content-sanity test

Added `soft404HtmlErrorPage_recordsFailureWithoutAdvancingValidatorsOrStoringItems` to `ca.feedline.service.FeedPollerServiceTest`.

A 200 whose body is an HTML error page is treated as a parse failure: error class set to `PARSE`, validators (`ETag`/`Last-Modified`) left untouched, zero items stored, and `ItemIngestService`/`IngestSkipRecorder` never invoked. Green by absence of a successful empty poll.

## Verify of record

`mvn clean verify` on a clean tree at branch tip:

```
Tests run: 174, Failures: 0, Errors: 0, Skipped: 0
BugInstance size is 0
Error size is 0
BUILD SUCCESS
```

Suite total rose from 172 to 174 (exactly the two new tests).

## Push proof

Branch `smoke-slice` pushed to origin with the two new tests and this report.
HOLD — no merge, no cleanup deletions.
