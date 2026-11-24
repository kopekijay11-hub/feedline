package ca.feedline.dto;

import java.time.Instant;

public record ClusterMemberDto(
        Long itemId,
        String sourceName,
        String title,
        String link,
        Instant publishedAt,
        Instant fetchedAt
) {
}
