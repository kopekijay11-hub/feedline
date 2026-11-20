package ca.feedline.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Minimal robots.txt parser supporting User-agent, Disallow, and Allow directives.
 *
 * <p>This parser implements longest-match-wins for path matching. It only supports
 * the minimal subset required for feed fetching: User-agent, Disallow, Allow.
 *
 * <p>Fail-closed policy: crawling is allowed only on HTTP 200 (when the parsed
 * rules permit it) or HTTP 404 (no robots.txt exists). HTTP 403, 5xx, other
 * non-200 statuses, and network/timeout errors disallow the crawl. Successful
 * results are cached per origin for 24 hours, so 404s and rules are re-evaluated
 * daily. Errors are never cached, allowing the next poll to retry.
 */
public class RobotsTxtParser {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxtParser.class);
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    private static final int MAX_ROBOTS_BYTES = 1024 * 1024;

    private final ConcurrentMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final FeedFetchConfig config;
    private final FeedHttpClient httpClient;
    private final Clock clock;

    public RobotsTxtParser(FeedFetchConfig config) {
        this(config, Clock.systemUTC());
    }

    public RobotsTxtParser(FeedFetchConfig config, Clock clock) {
        this.config = config;
        this.httpClient = new FeedHttpClient(config);
        this.clock = clock;
    }

    /**
     * Check if a path is allowed for a generic agent on the given origin.
     *
     * @param origin the origin (scheme + "://" + host)
     * @param path the path to check
     * @return true if allowed, false if disallowed or robots.txt could not be read
     */
    public boolean isAllowed(String origin, String path) {
        RobotsRules rules = getRules(origin);
        return rules != null && rules.isAllowed(path);
    }

    /**
     * Get the robots rules for an origin, using a 24-hour TTL cache if available.
     *
     * @param origin the origin (scheme + "://" + host)
     * @return the robots rules, never {@code null}; errors yield {@link RobotsRules#DENY_ALL}
     */
    public RobotsRules getRules(String origin) {
        if (origin == null || origin.isBlank()) {
            return RobotsRules.DENY_ALL;
        }

        Instant now = Instant.now(clock);
        CacheEntry cached = cache.get(origin);
        if (cached != null) {
            if (now.isBefore(cached.expiresAt())) {
                return cached.rules();
            }
            cache.remove(origin, cached);
        }

        Outcome outcome = fetchRules(origin);
        if (outcome.cacheable()) {
            cache.put(origin, new CacheEntry(outcome.rules(), Instant.now(clock).plus(CACHE_TTL)));
        }
        return outcome.rules();
    }

    private Outcome fetchRules(String origin) {
        String robotsUrl = origin + "/robots.txt";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(robotsUrl))
                    .timeout(config.readTimeout())
                    .header("User-Agent", buildUserAgent())
                    .GET()
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request);
            try (InputStream body = response.body()) {
                int statusCode = response.statusCode();
                if (statusCode == 200) {
                    if (body == null) {
                        return Outcome.DENIED;
                    }
                    byte[] bytes = body.readNBytes(MAX_ROBOTS_BYTES + 1);
                    if (bytes.length > MAX_ROBOTS_BYTES) {
                        log.warn("robots.txt from {} exceeds {} bytes - disallowing", robotsUrl, MAX_ROBOTS_BYTES);
                        return Outcome.DENIED;
                    }
                    return new Outcome(parse(new String(bytes, StandardCharsets.UTF_8)), true);
                }
                if (statusCode == 404) {
                    return new Outcome(RobotsRules.ALLOW_ALL, true);
                }
                log.warn("robots.txt fetch from {} returned HTTP {} - disallowing", robotsUrl, statusCode);
                return Outcome.DENIED;
            }
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Unable to read robots.txt from {} - disallowing: {}", robotsUrl, e.getMessage());
            return Outcome.DENIED;
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

            String key = parts[0].trim().toLowerCase(Locale.ROOT);
            String value = parts[1].trim();

            switch (key) {
                case "user-agent":
                    currentUserAgent = value;
                    break;
                case "disallow":
                    if (value.isEmpty()) {
                        rules.add(new Rule(currentUserAgent, "", true));
                    } else {
                        rules.add(new Rule(currentUserAgent, value, false));
                    }
                    break;
                case "allow":
                    rules.add(new Rule(currentUserAgent, value, true));
                    break;
                default:
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

    private record CacheEntry(RobotsRules rules, Instant expiresAt) {
    }

    private record Outcome(RobotsRules rules, boolean cacheable) {
        static final Outcome DENIED = new Outcome(RobotsRules.DENY_ALL, false);
    }

    /**
     * Represents the rules from a robots.txt file.
     */
    public static class RobotsRules {
        static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());
        static final RobotsRules DENY_ALL = new RobotsRules(List.of(new Rule("*", "", false)));

        private final List<Rule> rules;

        public RobotsRules(List<Rule> rules) {
            this.rules = rules;
        }

        public boolean isAllowed(String path) {
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

            return true;
        }
    }

    /**
     * A single rule in robots.txt.
     */
    private record Rule(String userAgent, String path, boolean allowed) {
        public boolean matches(String targetPath) {
            return path.isEmpty() || targetPath.startsWith(path);
        }
    }
}
