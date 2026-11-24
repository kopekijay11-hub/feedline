package ca.feedline.dto;

import java.time.Instant;
import java.util.List;

public record ClusterDetailDto(
        Long id,
        String representativeTitle,
        Long representativeItemId,
        int memberCount,
        List<String> sourceNames,
        Instant firstSeenAt,
        Instant lastSeenAt,
        List<ClusterMemberDto> members
) {
}
