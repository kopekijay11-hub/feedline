package ca.feedline.dto;

import java.time.Instant;

public record ItemIngestedEvent(int schemaVersion, String eventKey, String eventType, Long itemId, Long sourceId,
                                String guid, String title, String link, Instant publishedAt, Instant occurredAt,
                                Instant ingestedAt) {
}
