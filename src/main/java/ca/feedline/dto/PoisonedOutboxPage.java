package ca.feedline.dto;

import java.util.List;

public record PoisonedOutboxPage(
        List<PoisonedOutboxEventDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
    public PoisonedOutboxPage {
        items = List.copyOf(items);
    }

    @Override
    public List<PoisonedOutboxEventDto> items() {
        return List.copyOf(items);
    }
}
