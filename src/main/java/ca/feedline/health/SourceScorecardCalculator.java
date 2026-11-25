package ca.feedline.health;

import java.util.ArrayList;
import java.util.List;

public final class SourceScorecardCalculator {

    private SourceScorecardCalculator() {
    }

    public static SourceScorecard score(SourceHealthDetail detail) {
        if (detail == null) {
            throw new IllegalArgumentException("detail must not be null");
        }
        if ("DISABLED".equals(detail.state()) || detail.lastAttemptAt() == null) {
            return new SourceScorecard(detail.id(), detail.name(), detail.state(), null, null, List.of());
        }

        List<ScorecardDeduction> deductions = new ArrayList<>();

        if ("ROBOTS".equals(detail.lastErrorClass())) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.ROBOTS_BLOCKED, 100,
                    "lastErrorClass=ROBOTS"));
        }

        int statePoints = switch (detail.state()) {
            case "DEGRADED" -> 40;
            case "BACKOFF" -> 40;
            case "UNKNOWN" -> 60;
            case "UP" -> 0;
            default -> 0;
        };
        if (statePoints > 0) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.STATE, statePoints,
                    "state=" + detail.state()));
        }

        int failurePoints = Math.min(15, Math.max(0, detail.consecutiveFailures()) * 5);
        if (failurePoints > 0) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.CONSECUTIVE_FAILURES, failurePoints,
                    "consecutiveFailures=" + detail.consecutiveFailures()));
        }

        if ("STALE".equals(detail.contentFreshness())) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.FRESHNESS, 25,
                    "contentFreshness=STALE"));
        } else if ("UNKNOWN".equals(detail.contentFreshness())) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.FRESHNESS, 10,
                    "contentFreshness=UNKNOWN"));
        }

        if (!detail.validatorsPresent()) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.VALIDATORS_ABSENT, 5,
                    "validatorsPresent=false"));
        }

        Long secondsSinceLastSuccess = detail.secondsSinceLastSuccess();
        if (secondsSinceLastSuccess != null) {
            long threshold = detail.pollIntervalSeconds() * 10L;
            if (secondsSinceLastSuccess > threshold) {
                deductions.add(new ScorecardDeduction(ScorecardDeductionRule.SUCCESS_STALE, 15,
                        "secondsSinceLastSuccess=" + secondsSinceLastSuccess
                                + " > 10xPollIntervalSeconds=" + threshold));
            }
        }

        if (detail.skippedItemCount() > 0) {
            deductions.add(new ScorecardDeduction(ScorecardDeductionRule.SKIPPED_ITEMS, 5,
                    "skippedItemCount=" + detail.skippedItemCount() + " (lifetime count; v1 limitation)"));
        }

        int totalDeduction = 0;
        for (ScorecardDeduction deduction : deductions) {
            totalDeduction += deduction.points();
        }
        int score = Math.max(0, 100 - totalDeduction);
        String grade = gradeFor(score);
        return new SourceScorecard(detail.id(), detail.name(), detail.state(), grade, score, List.copyOf(deductions));
    }

    static String gradeFor(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 75) {
            return "B";
        }
        if (score >= 60) {
            return "C";
        }
        if (score >= 40) {
            return "D";
        }
        return "F";
    }
}
