base: 5b36f2a04e74643d9cf851f86ab155740d2fba02 == ls-remote 5b36f2a04e74643d9cf851f86ab155740d2fba02

## Verification
mvn clean verify on clean tree at committed branch tip; git status --short EMPTY before and after.
Surefire: Tests run: 243, Failures: 0, Errors: 0, Skipped: 0
SpotBugs: No bugs found.

## B1a — view switching
- Code commit: f63e69f (parent of this report commit).
- Applied as ordered: view.js added (localStorage key
  feedline.view; location.hash wins over storage, storage
  wins over the Reader default; replaceState on switch;
  hashchange honoured). index.html: health, sources and
  admin panels moved into #view-ops; Reader panel order is
  clusters, items, subscribe; alternate links fixed
  (Business & Markets and the duplicate World removed,
  Trading added); /view.js script tag added.
- Seam test: viewSwitchingAssetsPresent (asserts the /view.js reference and
  the feedline.view key).
- Verification (operator-run, clean tree at f63e69f, git
  status --short EMPTY before and after): Tests run: 244,
  Failures: 0, Errors: 0, Skipped: 0. SpotBugs: 0 bugs.

## B1d — top stories by recency
- code commit: 31530af
- tests: none added (existing none pinned order)
- verify: running/not completed
