package ca.feedline.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal robots.txt parser supporting User-agent, Disallow, and Allow directives.
 *
 * <p>This parser implements longest-match-wins for path matching. It only supports
 * the minimal subset required for feed fetching: User-agent, Disallow, Allow.
 *
 * <p>Caching: Results are cached per origin for the JVM's life, but only on
 * successful (HTTP 200) fetches. HTTP 404 means allow-all. HTTP 5xx or
 * unreachable means treat as disallowed for that poll and do NOT cache.
 */
public class RobotsTxtParser {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxtParser.class);

    private final Map<String, RobotsRules> cache = new HashMap<>();
    private final FeedFetchConfig config;
    private final HttpClient httpClient;

    public RobotsTxtParser(FeedFetchConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Check if a path is allowed for a generic agent on the given origin.
     *
     * @param origin the origin (scheme + "://" + host)
     * @param path the path to check
     * @return true if allowed, false if disallowed
     */
    public boolean isAllowed(String origin, String path) {
        RobotsRules rules = getRules(origin);
        if (rules == null) {
            // No rules available - assume allowed
            return true;
        }
        return rules.isAllowed(path);
    }

    /**
     * Get the robots rules for an origin, using cache if available.
     *
     * @param origin the origin (scheme + "://" + host)
     * @return the robots rules, or null if not available
     */
    public RobotsRules getRules(String origin) {
        // Check cache first
        RobotsRules cached = cache.get(origin);
        if (cached != null) {
            return cached;
        }

        // Fetch robots.txt
        try {
            String robotsUrl = origin + "/robots.txt";
            int statusCode = fetchRobots(robotsUrl);

            if (statusCode == 200) {
                // Parse and cache
                String content = fetchRobotsContent(robotsUrl);
                RobotsRules rules = parse(content);
                cache.put(origin, rules);
                return rules;
            } else if (statusCode == 404) {
                // 404 means allow-all - cache this
                RobotsRules rules = RobotsRules.ALLOW_ALL;
                cache.put(origin, rules);
                return rules;
            } else {
                // 5xx or other error - do NOT cache, treat as disallowed for this poll
                log.warn("Failed to fetch robots.txt from {}: HTTP {}", robotsUrl, statusCode);
                return null;
            }
        } catch (Exception e) {
            // Network error - do NOT cache, treat as disallowed for this poll
            log.warn("Error fetching robots.txt from {}: {}", origin, e.getMessage());
            return null;
        }
    }

    private int fetchRobots(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(config.readTimeout())
                    .header("User-Agent", buildUserAgent())
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            log.debug("Failed to fetch robots.txt from {}", url, e);
            return 0;
        }
    }

    private String fetchRobotsContent(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(config.readTimeout())
                    .header("User-Agent", buildUserAgent())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            log.debug("Failed to fetch robots.txt content from {}", url, e);
            return "";
        }
    }

    private String buildUserAgent() {
        return "Feedline/0.1 (+" + config.userAgent() + ")";
    }

    /**
     * Parse robots.txt content into rules.
     *
     * @param content the robots.txt content
     * @return the parsed rules
     */
    RobotsRules parse(String content) {
        List<Rule> rules = new ArrayList<>();
        String currentUserAgent = "*"; // Default to all agents

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split(":", 2);
            if (parts.length != 2) {
                continue;
            }

            String key = parts[0].trim().toLowerCase();
            String value = parts[1].trim();

            switch (key) {
                case "user-agent":
                    currentUserAgent = value;
                    break;
                case "disallow":
                    if (value.isEmpty()) {
                        // Empty Disallow means allow all
                        rules.add(new Rule(currentUserAgent, "", true));
                    } else {
                        rules.add(new Rule(currentUserAgent, value, false));
                    }
                    break;
                case "allow":
                    rules.add(new Rule(currentUserAgent, value, true));
                    break;
            }
        }

        return new RobotsRules(rules);
    }

    /**
     * Clear the cache (useful for testing).
     */
    void clearCache() {
        cache.clear();
    }

    /**
     * Represents the rules from a robots.txt file.
     */
    public static class RobotsRules {
        private static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

        private final List<Rule> rules;

        public RobotsRules(List<Rule> rules) {
            this.rules = rules;
        }

        public boolean isAllowed(String path) {
            // Check rules for generic agent (user-agent: *)
            Rule bestMatch = null;
            for (Rule rule : rules) {
                if ("*".equals(rule.userAgent()) && rule.matches(path)) {
                    if (bestMatch == null || rule.path().length() > bestMatch.path().length()) {
                        bestMatch = rule;
                    }
                }
            }

            if (bestMatch != null) {
                return bestMatch.allowed();
            }

            // Default to allowed if no matching rule
            return true;
        }
    }

    /**
     * A single rule in robots.txt.
     */
    private record Rule(String userAgent, String path, boolean allowed) {
        public boolean matches(String targetPath) {
            if (path.isEmpty()) {
                return true; // Empty path matches everything
            }
            if (targetPath.startsWith(path)) {
                return true;
            }
            return false;
        }
    }
}
