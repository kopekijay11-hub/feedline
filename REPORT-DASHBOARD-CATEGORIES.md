# Report — Dashboard Categories (Middle Route)

Branch: `dashboard-categories` (base `b86dd22`).

## Changed files
- `src/main/java/ca/feedline/web/ItemsController.java` — added `sources` query param; mutual-exclusion with `source`; validation (non-numeric, >50, empty).
- `src/main/java/ca/feedline/service/ItemQueryService.java` — single `source_id IN (...)` query; overloads for legacy `source` and new `sources`.
- `src/main/resources/static/categories.json` — new static category map keyed by exact configured source names.
- `src/main/resources/static/index.html` — chip row + grouped multi-select source picker (no numeric IDs).
- `src/main/resources/static/ops.js` — category resolution, chips/picker sync, URL filter state, Other bucket.
- `src/main/resources/static/ops.css` — chip/picker styles.
- `src/test/java/ca/feedline/CategoriesPartitionTest.java` — new JUnit partition test.

## New tests
- `CategoriesPartitionTest` — every configured source name appears in exactly one category; every mapped name exists in config.

## Live evidence
- Multi-source: `sources=<idA>,<idB>` total = 8; single-source totals 3 + 5 = 8 (exact sum). Paging union has no duplicates/gaps.
- Legacy: `source=<idA>` returns total 3.
- Validation: `sources=abc` returns HTTP 400.
- Strict binding: `sources` accepted; bogus param rejected.

## Verify of record
Command: `mvn clean verify` on clean tree at branch tip.

Surefire verdict line:
```
Tests run: 175, Failures: 0, Errors: 0, Skipped: 0
```

SpotBugs verdict lines:
```
BugInstance size is 0
No errors/warnings found
```
BUILD SUCCESS.
