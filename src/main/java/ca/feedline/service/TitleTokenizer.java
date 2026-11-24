package ca.feedline.service;

import java.util.Set;

public final class TitleTokenizer {
    private TitleTokenizer() {}

    public static Set<String> normalize(String title) { return StoryClusterService.normalizeTitle(title); }
    public static Set<String> normalizeTitle(String title) { return StoryClusterService.normalizeTitle(title); }
    public static Set<String> tokens(String title) { return StoryClusterService.normalizeTitle(title); }
    public static Set<String> titleTokens(String title) { return StoryClusterService.normalizeTitle(title); }
    public static Set<String> tokenize(String title) { return StoryClusterService.normalizeTitle(title); }

    public static double jaccard(Set<String> a, Set<String> b) { return StoryClusterService.jaccardSimilarity(a, b); }
    public static double jaccardSimilarity(Set<String> a, Set<String> b) { return StoryClusterService.jaccardSimilarity(a, b); }

    public static String wireAttribution(String summary) { return StoryClusterService.wireCredit(summary); }
    public static String attribution(String summary) { return StoryClusterService.wireCredit(summary); }
    public static String wireCredit(String summary) { return StoryClusterService.wireCredit(summary); }
}
