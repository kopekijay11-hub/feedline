package ca.feedline.dto;

import java.time.Instant;

public record ItemDto(
        Long id,
        Long sourceId,
        String guid,
        String link,
        String title,
        String summary,
        Instant publishedAt,
        Instant fetchedAt,
        Long clusterId
) {
}
