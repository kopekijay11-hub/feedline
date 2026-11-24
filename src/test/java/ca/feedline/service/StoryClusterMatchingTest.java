package ca.feedline.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StoryClusterMatchingTest {

    @Test
    void normalizeDropsStopwordsAndShortTokens() {
        Set<String> tokens = StoryClusterService.normalize("The A An of To In On For And Or At By With As Is Are Was Were big small");
        assertThat(tokens).containsExactlyInAnyOrder("big", "small");
    }

    @Test
    void normalizeIsCaseInsensitiveAndUnicode() {
        assertThat(StoryClusterService.normalize("Café & Café 123 x")).containsExactlyInAnyOrder("café", "123");
    }

    @Test
    void jaccardEdges() {
        Set<String> a = Set.of("a", "b", "c");
        Set<String> b = Set.of("a", "b", "c", "d", "e");
        assertThat(StoryClusterService.jaccard(a, b)).isEqualTo(0.60);
        assertThat(StoryClusterService.jaccard(b, a)).isEqualTo(0.60);

        Set<String> c = Set.of("a", "b", "c", "d", "e", "f");
        assertThat(StoryClusterService.jaccard(a, c)).isEqualTo(0.5);
    }

    @Test
    void jaccardEmpty() {
        assertThat(StoryClusterService.jaccard(Set.of(), Set.of())).isEqualTo(1.0);
        assertThat(StoryClusterService.jaccard(Set.of("a"), Set.of())).isEqualTo(0.0);
    }

    @Test
    void wireAttributionExtractsCreditWithin120Chars() {
        assertThat(StoryClusterService.wireAttribution("(AP) story here")).isEqualTo("AP");
        assertThat(StoryClusterService.wireAttribution("LONDON (Reuters) - story here")).isEqualTo("Reuters");
        assertThat(StoryClusterService.wireAttribution("no credit here")).isNull();
        String longSummary = "x".repeat(130) + " (AFP)";
        assertThat(StoryClusterService.wireAttribution(longSummary)).isNull();
        String within = "x".repeat(100) + " (CP) end";
        assertThat(StoryClusterService.wireAttribution(within)).isEqualTo("CP");
    }

    @Test
    void aliasClassesDelegate() {
        assertThat(TitleNormalizer.normalizeTitle("the alpha beta")).isEqualTo(StoryClusterService.normalizeTitle("the alpha beta"));
        assertThat(TitleTokenizer.tokens("the alpha beta")).isEqualTo(StoryClusterService.normalizeTitle("the alpha beta"));
        assertThat(TitleTokenizer.tokenize("the alpha beta")).isEqualTo(StoryClusterService.normalizeTitle("the alpha beta"));
        assertThat(StoryClusterMatcher.jaccard(Set.of("a"), Set.of("a"))).isEqualTo(1.0);
        assertThat(StoryClusterMatcher.wireAttribution("(Reuters) x")).isEqualTo("Reuters");
        assertThat(StoryClustering.attribution("(AP) x")).isEqualTo("AP");
    }
}
