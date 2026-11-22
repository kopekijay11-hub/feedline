# REPORT-P0-ADMIN-TOKEN

## Fix

P0: committed default admin token (`feedline-admin-dev-token`) removed.

- `src/main/java/ca/feedline/config/FeedProperties.java`: `Admin.apiKey` now defaults to `null`.
- `src/main/resources/application.yml`: `feed.admin.api-key: ${FEED_ADMIN_API_KEY}` with no fallback.
- `src/main/java/ca/feedline/web/AdminTokenFilter.java`: fail-closed with `401` when configured key is null/blank.

Boot without `FEED_ADMIN_API_KEY` does not fail startup; public API and ingestion remain available; admin surface closed (`401`).

## Tests

- No key configured: all admin routes return `401` without token and with `feedline-admin-dev-token` as token (`AdminTokenDefaultTest`).
- Key configured via property: wrong token rejected (`401`), right token succeeds on stats route (`AdminTokenConfiguredTest`).
- Grep proof: `grep -R "feedline-admin-dev-token" src/` returns no matches.

## Docs

README admin section states `FEED_ADMIN_API_KEY` is required and admin API disabled without it; no example value.

## Verification of record

Command: `mvn clean verify` on clean tree at branch tip.

Surefire: `Tests run: 162, Failures: 0, Errors: 0, Skipped: 0`

SpotBugs: `BugInstance size is 0` / `No errors/warnings found`

Branch: `fix/admin-token-default`. No merge performed.
