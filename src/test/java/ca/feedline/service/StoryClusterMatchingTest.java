package ca.feedline.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StoryClusterMatchingTest {

    @Test
    void normalizeTitleDropsStopwordsAndShortTokens() {
        Set<String> tokens = StoryClusterService.normalizeTitle("The A An of To In On For And Or At By With As Is Are Was Were big small");
        assertThat(tokens).containsExactlyInAnyOrder("big", "small");
    }

    @Test
    void normalizeTitleIsCaseInsensitiveAndUnicode() {
        assertThat(StoryClusterService.normalizeTitle("Café & Café 123 x")).containsExactlyInAnyOrder("café", "123");
    }

    @Test
    void jaccardSimilarityEdges() {
        Set<String> a = Set.of("a", "b", "c");
        Set<String> b = Set.of("a", "b", "c", "d", "e");
        assertThat(StoryClusterService.jaccardSimilarity(a, b)).isEqualTo(0.60);
        assertThat(StoryClusterService.jaccardSimilarity(b, a)).isEqualTo(0.60);

        Set<String> c = Set.of("a", "b", "c", "d", "e", "f");
        assertThat(StoryClusterService.jaccardSimilarity(a, c)).isEqualTo(0.5);
    }

    @Test
    void jaccardSimilarityEmpty() {
        assertThat(StoryClusterService.jaccardSimilarity(Set.of(), Set.of())).isEqualTo(1.0);
        assertThat(StoryClusterService.jaccardSimilarity(Set.of("a"), Set.of())).isEqualTo(0.0);
    }

    @Test
    void wireCreditExtractsCreditWithin120Chars() {
        assertThat(StoryClusterService.wireCredit("(AP) story here")).isEqualTo("AP");
        assertThat(StoryClusterService.wireCredit("LONDON (Reuters) - story here")).isEqualTo("Reuters");
        assertThat(StoryClusterService.wireCredit("no credit here")).isNull();
        String longSummary = "x".repeat(130) + " (AFP)";
        assertThat(StoryClusterService.wireCredit(longSummary)).isNull();
        String within = "x".repeat(100) + " (CP) end";
        assertThat(StoryClusterService.wireCredit(within)).isEqualTo("CP");
    }
}
