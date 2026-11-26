base: 810e9cc079094465e82e3bd725923da8d0965781 == ls-remote 810e9cc079094465e82e3bd725923da8d0965781

# Trading View Expansion Report

## Survey + Step 0 addendum evidence
9 ADD-CANDIDATE; StatCan slices 3 pass + trade stale; FLAGGED full Daily; rejection list.

## Quality rulings
Seeking Alpha rejected (7 sampled, 7 stub, threshold >5 of 20).
Benzinga admitted ON PROBATION (20 sampled, 0 stub, 0 promoted); probation terms: scorecard grade + fresh 20-item sample reviewed after 14 days of live polling; failure removes it by normal config route.
CFTC enforcement feed rejected as quality ruling.

## Category composition
BEFORE: 9 categories, 40 sources.
AFTER: 9 categories, 51 sources; Trading 15.

## Rulings
R1 StatCan slices.
R2 quality protocol.
R3 cadence class.
R4 move + 301 redirect /feed/business-markets.xml -> /feed/trading.xml.

## Cadence-class design
Declared in FeedProperties.Source and FeedSource (transient). SourceHealthService.contentFreshnessThreshold(FeedSource) reads it: 7 d daily, 35 d low-frequency-official. Proven by SourceHealthSeamTest.lowFrequencyNineteenDaysIsCurrent (19-day low-frequency CURRENT) and SourceHealthSeamTest.dailyNineteenDaysIsStale (19-day daily STALE). The daily test did not exist and was added in this pass (test-only change).

## Test mapping
CategoryFeedSeamTest redirect test; updated partition expectations; SourceHealthSeamTest low-frequency/current and daily/stale tests.

## Verification
mvn clean verify on clean tree at committed branch tip; git status --short EMPTY before and after.
Surefire: Tests run: 243, Failures: 0, Errors: 0, Skipped: 0.
SpotBugs: BUILD SUCCESS (0 bugs).

## Standing notes
1. published_at vs fetched_at distinction.
2. category and tags resolved from sourceId via config.
3. headline + summary both stored, unchanged.
