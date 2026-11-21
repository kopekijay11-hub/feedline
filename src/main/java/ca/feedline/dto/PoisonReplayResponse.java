package ca.feedline.dto;

import java.util.List;

public record PoisonReplayResponse(
        int replayedCount,
        List<Long> replayedIds,
        List<Long> skippedIds) {
    public PoisonReplayResponse {
        replayedIds = List.copyOf(replayedIds);
        skippedIds = List.copyOf(skippedIds);
    }

    @Override
    public List<Long> replayedIds() {
        return List.copyOf(replayedIds);
    }

    @Override
    public List<Long> skippedIds() {
        return List.copyOf(skippedIds);
    }
}
