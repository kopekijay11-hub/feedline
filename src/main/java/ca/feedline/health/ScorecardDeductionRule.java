package ca.feedline.health;

public enum ScorecardDeductionRule {
    ROBOTS_BLOCKED,
    STATE,
    CONSECUTIVE_FAILURES,
    FRESHNESS,
    VALIDATORS_ABSENT,
    SUCCESS_STALE,
    SKIPPED_ITEMS
}
