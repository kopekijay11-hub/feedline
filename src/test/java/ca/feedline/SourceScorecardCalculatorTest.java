package ca.feedline;

import ca.feedline.health.ScorecardDeductionRule;
import ca.feedline.health.SourceHealthDetail;
import ca.feedline.health.SourceScorecard;
import ca.feedline.health.SourceScorecardCalculator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class SourceScorecardCalculatorTest {

    private static SourceHealthDetail detail(String state, String lastErrorClass, int consecutiveFailures,
                                             String freshness, boolean validatorsPresent,
                                             Long secondsSinceLastSuccess, long pollIntervalSeconds,
                                             long skippedItemCount, boolean attempted) {
        return new SourceHealthDetail(
                1L,
                "Test Source",
                state,
                attempted ? Instant.parse("2026-11-25T00:00:00Z") : null,
                Instant.parse("2026-11-25T00:00:00Z"),
                lastErrorClass,
                null,
                null,
                null,
                false,
                consecutiveFailures,
                validatorsPresent,
                secondsSinceLastSuccess,
                Instant.parse("2026-11-25T00:00:00Z"),
                freshness,
                pollIntervalSeconds,
                skippedItemCount,
                0L
        );
    }

    @Test
    void robotsBlockedDominates() {
        SourceScorecard scorecard = SourceScorecardCalculator.score(
                detail("DEGRADED", "ROBOTS", 5, "STALE", false, 999999L, 60, 10, true));
        assertThat(scorecard.grade()).isEqualTo("F");
        assertThat(scorecard.score()).isEqualTo(0);
        assertThat(scorecard.deductions()).anyMatch(d -> d.rule() == ScorecardDeductionRule.ROBOTS_BLOCKED && d.points() == 100);
        int sum = scorecard.deductions().stream().mapToInt(d -> d.points()).sum();
        assertThat(sum).isGreaterThan(100);
        assertThat(scorecard.score()).isEqualTo(Math.max(0, 100 - sum));
    }

    @Test
    void stateDeductions() {
        assertThat(SourceScorecardCalculator.score(detail("DEGRADED", null, 0, "CURRENT", true, 10L, 60, 0, true)).deductions())
                .anyMatch(d -> d.rule() == ScorecardDeductionRule.STATE && d.points() == 40);
        assertThat(SourceScorecardCalculator.score(detail("BACKOFF", "HTTP_429", 0, "CURRENT", true, 10L, 60, 0, true)).deductions())
                .anyMatch(d -> d.rule() == ScorecardDeductionRule.STATE && d.points() == 40);
        assertThat(SourceScorecardCalculator.score(detail("UNKNOWN", null, 0, "CURRENT", true, 10L, 60, 0, true)).deductions())
                .anyMatch(d -> d.rule() == ScorecardDeductionRule.STATE && d.points() == 60);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", true, 10L, 60, 0, true)).deductions())
                .noneMatch(d -> d.rule() == ScorecardDeductionRule.STATE);
    }

    @Test
    void consecutiveFailuresCappedAt15() {
        SourceScorecard two = SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", true, 10L, 60, 0, true));
        SourceScorecard three = SourceScorecardCalculator.score(detail("UP", null, 3, "CURRENT", true, 10L, 60, 0, true));
        SourceScorecard five = SourceScorecardCalculator.score(detail("UP", null, 5, "CURRENT", true, 10L, 60, 0, true));
        assertThat(two.deductions()).anyMatch(d -> d.rule() == ScorecardDeductionRule.CONSECUTIVE_FAILURES && d.points() == 10);
        assertThat(three.deductions()).anyMatch(d -> d.rule() == ScorecardDeductionRule.CONSECUTIVE_FAILURES && d.points() == 15);
        assertThat(five.deductions()).anyMatch(d -> d.rule() == ScorecardDeductionRule.CONSECUTIVE_FAILURES && d.points() == 15);
    }

    @Test
    void freshnessDeductions() {
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "STALE", true, 10L, 60, 0, true)).deductions())
                .anyMatch(d -> d.rule() == ScorecardDeductionRule.FRESHNESS && d.points() == 25);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "UNKNOWN", true, 10L, 60, 0, true)).deductions())
                .anyMatch(d -> d.rule() == ScorecardDeductionRule.FRESHNESS && d.points() == 10);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", true, 10L, 60, 0, true)).deductions())
                .noneMatch(d -> d.rule() == ScorecardDeductionRule.FRESHNESS);
    }

    @Test
    void validatorsAbsentDeduction() {
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", false, 10L, 60, 0, true)).deductions())
                .anyMatch(d -> d.rule() == ScorecardDeductionRule.VALIDATORS_ABSENT && d.points() == 5);
    }

    @Test
    void successStaleFiresOnlyAboveTenTimesInterval() {
        SourceScorecard atBoundary = SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", true, 600L, 60, 0, true));
        SourceScorecard aboveBoundary = SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", true, 601L, 60, 0, true));
        SourceScorecard absent = SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", true, null, 60, 0, true));

        assertThat(atBoundary.deductions()).noneMatch(d -> d.rule() == ScorecardDeductionRule.SUCCESS_STALE);
        assertThat(aboveBoundary.deductions()).anyMatch(d -> d.rule() == ScorecardDeductionRule.SUCCESS_STALE && d.points() == 15);
        assertThat(absent.deductions()).noneMatch(d -> d.rule() == ScorecardDeductionRule.SUCCESS_STALE);
    }

    @Test
    void skippedItemsDeductionLabelsLifetimeCount() {
        SourceScorecard scorecard = SourceScorecardCalculator.score(detail("UP", null, 0, "CURRENT", true, 10L, 60, 2, true));
        assertThat(scorecard.deductions()).anyMatch(d ->
                d.rule() == ScorecardDeductionRule.SKIPPED_ITEMS
                        && d.points() == 5
                        && d.detail().contains("lifetime count")
                        && d.detail().contains("v1 limitation"));
    }

    @Test
    void bandEdges() {
        // A >= 90, B >= 75, C >= 60, D >= 40, F < 40
        assertThat(scoreWithTotalDeduction(10).grade()).isEqualTo("A");  // 90
        assertThat(scoreWithTotalDeduction(11).grade()).isEqualTo("B");  // 89-ish via achievable 85 below; use exact achievable scores
        assertThat(scoreWithTotalDeduction(25).grade()).isEqualTo("B");  // 75
        assertThat(scoreWithTotalDeduction(26).grade()).isEqualTo("C");  // 74-ish via 70 below
        assertThat(scoreWithTotalDeduction(40).grade()).isEqualTo("C");  // 60
        assertThat(scoreWithTotalDeduction(41).grade()).isEqualTo("D");  // 59-ish via 55 below
        assertThat(scoreWithTotalDeduction(60).grade()).isEqualTo("D");  // 40
        assertThat(scoreWithTotalDeduction(61).grade()).isEqualTo("F");  // 39-ish via 35 below

        // exact boundaries with achievable score constructions
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", true, 10L, 60, 0, true)).score()).isEqualTo(90);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", true, 10L, 60, 0, true)).grade()).isEqualTo("A");
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", false, 10L, 60, 0, true)).score()).isEqualTo(85);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", false, 10L, 60, 0, true)).grade()).isEqualTo("B");
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "STALE", true, 10L, 60, 0, true)).score()).isEqualTo(75);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 0, "STALE", true, 10L, 60, 0, true)).grade()).isEqualTo("B");
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 1, "STALE", true, 10L, 60, 0, true)).score()).isEqualTo(70);
        assertThat(SourceScorecardCalculator.score(detail("UP", null, 1, "STALE", true, 10L, 60, 0, true)).grade()).isEqualTo("C");
        assertThat(SourceScorecardCalculator.score(detail("DEGRADED", null, 0, "CURRENT", true, 10L, 60, 0, true)).score()).isEqualTo(60);
        assertThat(SourceScorecardCalculator.score(detail("DEGRADED", null, 0, "CURRENT", true, 10L, 60, 0, true)).grade()).isEqualTo("C");
        assertThat(SourceScorecardCalculator.score(detail("DEGRADED", null, 1, "CURRENT", true, 10L, 60, 0, true)).score()).isEqualTo(55);
        assertThat(SourceScorecardCalculator.score(detail("DEGRADED", null, 1, "CURRENT", true, 10L, 60, 0, true)).grade()).isEqualTo("D");
        assertThat(SourceScorecardCalculator.score(detail("UNKNOWN", null, 0, "CURRENT", true, 10L, 60, 0, true)).score()).isEqualTo(40);
        assertThat(SourceScorecardCalculator.score(detail("UNKNOWN", null, 0, "CURRENT", true, 10L, 60, 0, true)).grade()).isEqualTo("D");
        assertThat(SourceScorecardCalculator.score(detail("UNKNOWN", null, 1, "CURRENT", true, 10L, 60, 0, true)).score()).isEqualTo(35);
        assertThat(SourceScorecardCalculator.score(detail("UNKNOWN", null, 1, "CURRENT", true, 10L, 60, 0, true)).grade()).isEqualTo("F");
    }

    @Test
    void blipCaseDegradedFreshNotF() {
        SourceScorecard scorecard = SourceScorecardCalculator.score(
                detail("DEGRADED", "HTTP_500", 3, "CURRENT", true, 30L, 60, 0, true));
        assertThat(scorecard.grade()).isEqualTo("D");
        assertThat(scorecard.score()).isEqualTo(45);
    }

    @Test
    void ungradedDisabledAndNeverAttempted() {
        SourceScorecard disabled = SourceScorecardCalculator.score(
                new SourceHealthDetail(1L, "Disabled", "DISABLED", Instant.parse("2026-11-25T00:00:00Z"),
                        null, null, null, null, null, false, 0, false, null, null, "UNKNOWN", 60, 0, 0));
        SourceScorecard neverPolled = SourceScorecardCalculator.score(
                detail("UNKNOWN", null, 0, "UNKNOWN", false, null, 60, 0, false));

        assertThat(disabled.grade()).isNull();
        assertThat(disabled.score()).isNull();
        assertThat(disabled.deductions()).isEmpty();

        assertThat(neverPolled.grade()).isNull();
        assertThat(neverPolled.score()).isNull();
        assertThat(neverPolled.deductions()).isEmpty();
        assertThat(neverPolled.state()).isEqualTo("UNKNOWN");
    }

    @Test
    void deductionRuleNamesAreClosedEnum() {
        assertThat(EnumSet.allOf(ScorecardDeductionRule.class))
                .containsExactlyInAnyOrder(
                        ScorecardDeductionRule.ROBOTS_BLOCKED,
                        ScorecardDeductionRule.STATE,
                        ScorecardDeductionRule.CONSECUTIVE_FAILURES,
                        ScorecardDeductionRule.FRESHNESS,
                        ScorecardDeductionRule.VALIDATORS_ABSENT,
                        ScorecardDeductionRule.SUCCESS_STALE,
                        ScorecardDeductionRule.SKIPPED_ITEMS);
    }

    private static SourceScorecard scoreWithTotalDeduction(int totalDeduction) {
        // Achievable deduction combos for boundary probing.
        return switch (totalDeduction) {
            case 10 -> SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", true, 10L, 60, 0, true)); // 90 A
            case 11 -> SourceScorecardCalculator.score(detail("UP", null, 2, "CURRENT", false, 10L, 60, 0, true)); // 85 B (nearest below 90)
            case 25 -> SourceScorecardCalculator.score(detail("UP", null, 0, "STALE", true, 10L, 60, 0, true)); // 75 B
            case 26 -> SourceScorecardCalculator.score(detail("UP", null, 1, "STALE", true, 10L, 60, 0, true)); // 70 C
            case 40 -> SourceScorecardCalculator.score(detail("DEGRADED", null, 0, "CURRENT", true, 10L, 60, 0, true)); // 60 C
            case 41 -> SourceScorecardCalculator.score(detail("DEGRADED", null, 1, "CURRENT", true, 10L, 60, 0, true)); // 55 D
            case 60 -> SourceScorecardCalculator.score(detail("UNKNOWN", null, 0, "CURRENT", true, 10L, 60, 0, true)); // 40 D
            case 61 -> SourceScorecardCalculator.score(detail("UNKNOWN", null, 1, "CURRENT", true, 10L, 60, 0, true)); // 35 F
            default -> throw new IllegalArgumentException("unsupported: " + totalDeduction);
        };
    }
}
