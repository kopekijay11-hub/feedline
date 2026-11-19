package ca.feedline.dto;

import java.util.List;

public record ItemsPage(
        List<ItemDto> items,
        String nextCursor,
        long total,
        boolean hasMore
) {
}
