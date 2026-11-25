package ca.feedline.health;

public record ScorecardDeduction(
        ScorecardDeductionRule rule,
        int points,
        String detail
) {
}
