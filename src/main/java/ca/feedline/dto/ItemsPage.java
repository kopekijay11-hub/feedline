package ca.feedline.dto;

import java.util.List;

public record ItemsPage(
        List<ItemDto> items,
        String nextCursor,
        long total,
        boolean hasMore
) {
    public ItemsPage {
        items = List.copyOf(items);
    }

    @Override
    public List<ItemDto> items() {
        return List.copyOf(items);
    }
}
