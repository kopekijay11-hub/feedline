# Report — Dashboard Picker Fix

Branch: `dashboard-picker-fix` (base `e926b40`).

## Change
Static-only hotfix in `src/main/resources/static/ops.js`:
- `filterSources` `change` handler now resets cursor and calls `loadItems(null, null)` after updating state/URL.
- Source rows in the Sources panel are clickable: selecting exactly that source, syncing picker/chips, updating URL, resetting cursor, reloading items. Rows carry `cursor: pointer` and title hint "Click to read this source", including DISABLED rows.

No backend changes.

## Verify of record
Command: `mvn clean verify` on clean tree at branch tip.

Surefire verdict line:
```
Tests run: 185, Failures: 0, Errors: 0, Skipped: 0
```

SpotBugs verdict lines:
```
BugInstance size is 0
No errors/warnings found
```
BUILD SUCCESS.
