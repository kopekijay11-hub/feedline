package ca.feedline.health;

import java.util.List;

public record SourceScorecard(
        Long id,
        String name,
        String state,
        String grade,
        Integer score,
        List<ScorecardDeduction> deductions
) {
    public SourceScorecard {
        deductions = deductions == null ? List.of() : List.copyOf(deductions);
    }
}
