package ca.feedline.service;

import ca.feedline.config.FeedProperties;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RobotsTxtParser {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxtParser.class);
    private static final int MAX_ROBOTS_BYTES = 1024 * 1024;

    private final Map<String, CacheEntry> cache;
    private final int cacheMaxEntries;
    private final Duration cacheTtl;
    private final FeedFetchConfig config;
    private final FeedHttpClient httpClient;
    private final Clock clock;
    private final String userAgent;

    public RobotsTxtParser(FeedFetchConfig config) {
        this(config, Clock.systemUTC());
    }

    public RobotsTxtParser(FeedFetchConfig config, Clock clock) {
        this(config, clock, 256, Duration.ofHours(1));
    }

    public RobotsTxtParser(FeedFetchConfig config, int cacheMaxEntries, Duration cacheTtl) {
        this(config, Clock.systemUTC(), cacheMaxEntries, cacheTtl);
    }

    public RobotsTxtParser(FeedFetchConfig config, int cacheMaxEntries, Duration cacheTtl, Clock clock) {
        this(config, clock, cacheMaxEntries, cacheTtl, null);
    }

    public RobotsTxtParser(FeedFetchConfig config, Clock clock, int cacheMaxEntries, Duration cacheTtl) {
        this(config, clock, cacheMaxEntries, cacheTtl, null);
    }

    public RobotsTxtParser(FeedFetchConfig config, Clock clock, int cacheMaxEntries, Duration cacheTtl,
                           String userAgent) {
        this(config, new FeedHttpClient(config), clock, cacheMaxEntries, cacheTtl, userAgent);
    }

    public RobotsTxtParser(FeedFetchConfig config, FeedProperties.Robots robots) {
        this(config, Clock.systemUTC(), robots);
    }

    public RobotsTxtParser(FeedFetchConfig config, Clock clock, FeedProperties.Robots robots) {
        this(config, robots, clock);
    }

    public RobotsTxtParser(FeedFetchConfig config, FeedProperties.Robots robots, Clock clock) {
        this(config, clock,
                robots == null ? 256 : robots.getCacheMaxEntries(),
                robots == null ? Duration.ofHours(1) : robots.getCacheTtl(),
                null);
    }

    RobotsTxtParser(FeedFetchConfig config, FeedHttpClient httpClient, Clock clock, int cacheMaxEntries,
                    Duration cacheTtl, String userAgent) {
        this.config = config;
        this.httpClient = httpClient;
        this.clock = clock;
        this.cacheMaxEntries = cacheMaxEntries <= 0 ? 256 : cacheMaxEntries;
        this.cacheTtl = cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()
                ? Duration.ofHours(1) : cacheTtl;
        this.userAgent = (userAgent == null || userAgent.isBlank())
                ? (config == null ? "Feedline/0.1" : "Feedline/0.1 (+" + config.userAgent() + ")")
                : userAgent;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > RobotsTxtParser.this.cacheMaxEntries;
            }
        });
    }

    public boolean isAllowed(String origin, String path) {
        RobotsRules rules = getRules(origin);
        return rules != null && rules.isAllowed(userAgent, path);
    }

    public RobotsRules getRules(String origin) {
        if (origin == null || origin.isBlank()) {
            return RobotsRules.DENY_ALL;
        }

        Instant now = clock.instant();
        synchronized (cache) {
            CacheEntry cached = cache.get(origin);
            if (cached != null) {
                if (now.isBefore(cached.expiresAt())) {
                    return cached.rules();
                }
                cache.remove(origin);
            }
        }

        Outcome outcome = fetchRules(origin);
        if (outcome.cacheable()) {
            synchronized (cache) {
                cache.put(origin, new CacheEntry(outcome.rules(), clock.instant().plus(cacheTtl)));
            }
        }
        return outcome.rules();
    }

    private Outcome fetchRules(String origin) {
        String robotsUrl = origin + "/robots.txt";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(robotsUrl))
                    .timeout(config.readTimeout())
                    .header("User-Agent", userAgent)
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
                    return new Outcome(parse(new String(bytes, StandardCharsets.UTF_8), userAgent), true);
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

    RobotsRules parse(String content) {
        return parse(content, userAgent);
    }

    RobotsRules parse(String content, String agent) {
        if (content == null) {
            return RobotsRules.ALLOW_ALL;
        }
        List<Group> groups = new ArrayList<>();
        List<String> agents = new ArrayList<>();
        List<Rule> rules = new ArrayList<>();

        for (String rawLine : content.split("\n")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                if (!agents.isEmpty() || !rules.isEmpty()) {
                    groups.add(new Group(new ArrayList<>(agents.isEmpty() ? List.of("*") : agents),
                            new ArrayList<>(rules)));
                    agents.clear();
                    rules.clear();
                }
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
                    if (!rules.isEmpty()) {
                        groups.add(new Group(new ArrayList<>(agents.isEmpty() ? List.of("*") : agents),
                                new ArrayList<>(rules)));
                        agents.clear();
                        rules.clear();
                    }
                    agents.add(value.isEmpty() ? "*" : value);
                    break;
                case "disallow":
                    if (agents.isEmpty()) {
                        agents.add("*");
                    }
                    String disallowPath = value;
                    boolean disallowAllowed = disallowPath.isEmpty();
                    for (String ua : agents) {
                        rules.add(new Rule(ua, disallowPath, disallowAllowed));
                    }
                    break;
                case "allow":
                    if (agents.isEmpty()) {
                        agents.add("*");
                    }
                    for (String ua : agents) {
                        rules.add(new Rule(ua, value, true));
                    }
                    break;
                default:
                    break;
            }
        }

        if (!agents.isEmpty() || !rules.isEmpty()) {
            groups.add(new Group(new ArrayList<>(agents.isEmpty() ? List.of("*") : agents),
                    new ArrayList<>(rules)));
        }

        if (groups.isEmpty()) {
            return RobotsRules.ALLOW_ALL;
        }
        return new RobotsRules(List.of(), groups, agent == null ? userAgent : agent);
    }

    void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private record CacheEntry(RobotsRules rules, Instant expiresAt) {
    }

    private record Outcome(RobotsRules rules, boolean cacheable) {
        static final Outcome DENIED = new Outcome(RobotsRules.DENY_ALL, false);
    }

    private record Group(List<String> userAgents, List<Rule> rules) {
    }

    public static class RobotsRules {
        static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());
        static final RobotsRules DENY_ALL = new RobotsRules(List.of(new Rule("*", "", false)));

        private final List<Rule> rules;
        private final List<Group> groups;
        private final String defaultUserAgent;

        public RobotsRules(List<Rule> rules) {
            this(rules, null, "*");
        }

        RobotsRules(List<Rule> rules, List<Group> groups, String defaultUserAgent) {
            this.rules = rules == null ? List.of() : List.copyOf(rules);
            this.groups = groups;
            this.defaultUserAgent = defaultUserAgent == null ? "" : defaultUserAgent;
        }

        public boolean isAllowed(String path) {
            return isAllowed(defaultUserAgent, path);
        }

        public boolean isAllowed(String userAgent, String path) {
            List<Rule> selected;
            if (groups == null) {
                selected = rules;
            } else {
                selected = selectRules(groups, userAgent);
                if (selected.isEmpty()) {
                    return true;
                }
            }
            return applyRules(selected, path);
        }

        private static List<Rule> selectRules(List<Group> groups, String userAgent) {
            String agentToken = productToken(userAgent);
            List<Group> specific = new ArrayList<>();
            List<Group> wildcard = new ArrayList<>();
            for (Group group : groups) {
                boolean hasWildcard = group.userAgents().stream().anyMatch(ua -> "*".equals(ua.trim()));
                boolean matchesAgent = !agentToken.isEmpty() && group.userAgents().stream()
                        .anyMatch(ua -> !"*".equals(ua.trim()) && userAgentMatches(ua, agentToken));
                if (matchesAgent) {
                    specific.add(group);
                } else if (hasWildcard) {
                    wildcard.add(group);
                }
            }
            List<Group> chosen = specific.isEmpty() ? wildcard : specific;
            List<Rule> result = new ArrayList<>();
            for (Group group : chosen) {
                result.addAll(group.rules());
            }
            return result;
        }

        private static boolean userAgentMatches(String groupUa, String agentToken) {
            String groupToken = productToken(groupUa);
            if (groupToken.isEmpty()) {
                return false;
            }
            if (groupToken.equalsIgnoreCase(agentToken)) {
                return true;
            }
            if (agentToken.regionMatches(true, 0, groupToken, 0, groupToken.length())) {
                return true;
            }
            if (groupToken.regionMatches(true, 0, agentToken, 0, agentToken.length())) {
                return true;
            }
            return false;
        }

        private static String productToken(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            String s = value.trim();
            int slash = s.indexOf('/');
            if (slash >= 0) {
                s = s.substring(0, slash);
            }
            int space = s.indexOf(' ');
            if (space >= 0) {
                s = s.substring(0, space);
            }
            return s;
        }

        private static boolean applyRules(List<Rule> rules, String path) {
            String target = path == null ? "" : path;
            Rule best = null;
            for (Rule rule : rules) {
                if (rule.matches(target)) {
                    if (best == null
                            || rule.path().length() > best.path().length()
                            || (rule.path().length() == best.path().length() && rule.allowed() && !best.allowed())) {
                        best = rule;
                    }
                }
            }
            if (best == null) {
                return true;
            }
            return best.allowed();
        }
    }

    public record Rule(String userAgent, String path, boolean allowed) {
        public boolean matches(String targetPath) {
            String tp = targetPath == null ? "" : targetPath;
            if (path == null || path.isEmpty()) {
                return true;
            }
            boolean anchored = path.endsWith("$");
            String core = anchored ? path.substring(0, path.length() - 1) : path;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < core.length(); i++) {
                char c = core.charAt(i);
                if (c == '*') {
                    sb.append(".*");
                } else {
                    sb.append("\\Q").append(c).append("\\E");
                }
            }
            String regex = sb.toString();
            if (anchored) {
                regex = regex + "\\z";
            }
            try {
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(tp);
                return anchored ? matcher.matches() : matcher.lookingAt();
            } catch (java.util.regex.PatternSyntaxException e) {
                return false;
            }
        }
    }
}
