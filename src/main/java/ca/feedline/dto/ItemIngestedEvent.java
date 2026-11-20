package ca.feedline.dto;

import java.time.Instant;

public record ItemIngestedEvent(Long itemId, Long sourceId, String guid, String title, String link,
                                Instant publishedAt, Instant ingestedAt) {
}
