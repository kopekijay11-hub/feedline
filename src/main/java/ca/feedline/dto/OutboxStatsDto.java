package ca.feedline.dto;

public record OutboxStatsDto(
        long pendingCount,
        long publishedCount,
        long poisonedCount) {
}
