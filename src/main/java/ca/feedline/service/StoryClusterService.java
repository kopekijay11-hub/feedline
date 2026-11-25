package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.Item;
import ca.feedline.domain.StoryCluster;
import ca.feedline.domain.StoryClusterMember;
import ca.feedline.dto.ClusterDetailDto;
import ca.feedline.dto.ClusterMemberDto;
import ca.feedline.dto.ClusterSummaryDto;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import ca.feedline.repository.StoryClusterMemberRepository;
import ca.feedline.repository.StoryClusterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(name = "feed.cluster.enabled", havingValue = "true", matchIfMissing = true)
public class StoryClusterService {

    private static final Logger log = LoggerFactory.getLogger(StoryClusterService.class);

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "at", "by", "with", "as", "is", "are", "was", "were");

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern CREDIT_PATTERN = Pattern.compile("\\((AP|Reuters|AFP|CP)\\)");

    private final ItemRepository items;
    private final StoryClusterRepository clusters;
    private final StoryClusterMemberRepository members;
    private final FeedProperties feedProperties;
    private final Clock clock;

    public StoryClusterService() {
        this(null, null, null, new FeedProperties(), Clock.systemUTC());
    }

    public StoryClusterService(Clock clock) {
        this(null, null, null, new FeedProperties(), clock == null ? Clock.systemUTC() : clock);
    }

    public StoryClusterService(FeedProperties feedProperties) {
        this(null, null, null, feedProperties == null ? new FeedProperties() : feedProperties, Clock.systemUTC());
    }

    public StoryClusterService(FeedProperties feedProperties, Clock clock) {
        this(null, null, null, feedProperties == null ? new FeedProperties() : feedProperties, clock == null ? Clock.systemUTC() : clock);
    }

    @Autowired
    public StoryClusterService(ItemRepository items, StoryClusterRepository clusters,
                              StoryClusterMemberRepository members, FeedProperties feedProperties, Clock clock) {
        this.items = items;
        this.clusters = clusters;
        this.members = members;
        this.feedProperties = feedProperties == null ? new FeedProperties() : feedProperties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    // ---------- text helpers (public static) ----------

    public static Set<String> normalizeTitle(String title) {
        Set<String> out = new HashSet<>();
        if (title == null) {
            return out;
        }
        Matcher m = TOKEN_PATTERN.matcher(title.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String tok = m.group();
            if (tok.length() < 3) {
                continue;
            }
            if (STOPWORDS.contains(tok)) {
                continue;
            }
            out.add(tok);
        }
        return out;
    }

    public static double jaccardSimilarity(Set<String> a, Set<String> b) {
        Set<String> left = a == null ? Set.of() : a;
        Set<String> right = b == null ? Set.of() : b;
        if (left.isEmpty() && right.isEmpty()) {
            return 1.0;
        }
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = new HashSet<>(left);
        inter.retainAll(right);
        return ((double) inter.size()) / ((double) union.size());
    }

    public static String wireCredit(String summary) {
        if (summary == null) {
            return null;
        }
        String head = summary.length() <= 120 ? summary : summary.substring(0, 120);
        Matcher m = CREDIT_PATTERN.matcher(head);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    // ---------- scheduling ----------

    @Scheduled(fixedDelayString = "${feed.cluster.pass-delay-ms:300000}")
    public void scheduledPass() {
        log.info("Story cluster pass started");
        long startedAt = System.nanoTime();
        int itemsConsidered = performPass();
        log.info("Story cluster pass completed in {} ms; items considered: {}",
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(), itemsConsidered);
    }

    @Transactional
    private int performPass() {
        if (!isEnabled()) {
            return 0;
        }
        if (items == null || clusters == null || members == null) {
            return 0;
        }
        FeedProperties.Cluster cfg = feedProperties.getCluster();
        int windowHours = cfg.getWindowHours();
        double titleThreshold = cfg.getTitleThreshold();
        double wireThreshold = cfg.getWireThreshold();

        // load items
        List<Item> allItems = new ArrayList<>(items.findAll());
        Map<Long, Item> itemById = new HashMap<>();
        for (Item it : allItems) {
            itemById.put(it.getId(), it);
        }

        // load existing clusters
        List<StoryCluster> dbClusters = new ArrayList<>(clusters.findAll());
        Map<Long, StoryCluster> clusterById = new HashMap<>();
        for (StoryCluster c : dbClusters) {
            clusterById.put(c.getId(), c);
        }
        Map<Long, List<StoryClusterMember>> membersByCluster = new HashMap<>();
        Set<Long> clusteredItemIds = new HashSet<>();
        for (StoryCluster c : dbClusters) {
            List<StoryClusterMember> ms = members.findByClusterId(c.getId());
            membersByCluster.put(c.getId(), new ArrayList<>(ms));
            for (StoryClusterMember m : ms) {
                clusteredItemIds.add(m.getItemId());
            }
        }

        // in-memory states
        class State {
            Long id; // null => new
            StoryCluster entity;
            List<Item> membersList = new ArrayList<>();
            State(Long id, StoryCluster entity) {
                this.id = id;
                this.entity = entity;
                if (id != null) {
                    for (StoryClusterMember m : membersByCluster.getOrDefault(id, List.of())) {
                        Item it = itemById.get(m.getItemId());
                        if (it != null) {
                            membersList.add(it);
                        }
                    }
                }
            }
        }
        List<State> states = new ArrayList<>();
        for (StoryCluster c : dbClusters) {
            states.add(new State(c.getId(), c));
        }

        // unclustered items sorted
        List<Item> unclustered = new ArrayList<>();
        for (Item it : allItems) {
            if (!clusteredItemIds.contains(it.getId())) {
                unclustered.add(it);
            }
        }
        unclustered.sort(Comparator.comparing(Item::getFetchedAt).thenComparing(Item::getId));

        List<Item> unclusteredProcessed = new ArrayList<>();

        for (Item x : unclustered) {
            // candidate states
            List<State> candidateStates = new ArrayList<>();
            for (State st : states) {
                for (Item y : st.membersList) {
                    if (matches(x, y, windowHours, titleThreshold, wireThreshold)) {
                        candidateStates.add(st);
                        break;
                    }
                }
            }
            // candidate unclustered processed
            List<Item> candidateUnclustered = new ArrayList<>();
            for (Item y : unclusteredProcessed) {
                if (matches(x, y, windowHours, titleThreshold, wireThreshold)) {
                    candidateUnclustered.add(y);
                }
            }

            if (candidateStates.isEmpty() && candidateUnclustered.isEmpty()) {
                unclusteredProcessed.add(x);
                continue;
            }

            if (!candidateStates.isEmpty()) {
                // target = min id
                State target = candidateStates.get(0);
                for (State st : candidateStates) {
                    if (st.id != null && (target.id == null || st.id < target.id)) {
                        target = st;
                    }
                }
                // merge other candidate states into target
                for (State other : new ArrayList<>(candidateStates)) {
                    if (other == target) {
                        continue;
                    }
                    // reassign members in DB
                    if (other.id != null && target.id != null) {
                        members.reassignCluster(other.id, target.id);
                    }
                    // move member items
                    for (Item it : other.membersList) {
                        if (target.membersList.stream().noneMatch(m -> m.getId().equals(it.getId()))) {
                            target.membersList.add(it);
                        }
                    }
                    if (other.id != null) {
                        clusters.deleteById(other.id);
                    }
                    states.remove(other);
                }
                // add candidate unclustered + x to target
                List<Item> toAdd = new ArrayList<>(candidateUnclustered);
                toAdd.add(x);
                for (Item it : toAdd) {
                    if (target.membersList.stream().anyMatch(m -> m.getId().equals(it.getId()))) {
                        continue;
                    }
                    // determine rule/similarity
                    String rule = null;
                    double sim = 0.0;
                    // try match against existing members before addition
                    Item matchAgainst = null;
                    for (Item y : new ArrayList<>(target.membersList)) {
                        if (matches(it, y, windowHours, titleThreshold, wireThreshold)) {
                            matchAgainst = y;
                            break;
                        }
                    }
                    if (matchAgainst != null) {
                        sim = jaccardSimilarity(normalizeTitle(it.getTitle()), normalizeTitle(matchAgainst.getTitle()));
                        rule = ruleFor(it, matchAgainst, sim, titleThreshold, wireThreshold);
                    } else if (it == x && !candidateUnclustered.isEmpty()) {
                        Item y = candidateUnclustered.get(0);
                        sim = jaccardSimilarity(normalizeTitle(it.getTitle()), normalizeTitle(y.getTitle()));
                        rule = ruleFor(it, y, sim, titleThreshold, wireThreshold);
                    } else {
                        // fallback: match with first member
                        if (!target.membersList.isEmpty()) {
                            Item y = target.membersList.get(0);
                            sim = jaccardSimilarity(normalizeTitle(it.getTitle()), normalizeTitle(y.getTitle()));
                            rule = ruleFor(it, y, sim, titleThreshold, wireThreshold);
                        }
                    }
                    if (rule == null) {
                        rule = "R1";
                    }
                    target.membersList.add(it);
                    // persist member
                    if (target.id != null) {
                        StoryClusterMember sm = new StoryClusterMember();
                        sm.setClusterId(target.id);
                        sm.setItemId(it.getId());
                        sm.setMatchedRule(rule);
                        sm.setSimilarity(sim);
                        sm.setJoinedAt(clock.instant());
                        members.save(sm);
                    }
                }
                // if target is new (null id) but candidateStates nonempty? target has id. okay.

                // update target entity
                if (target.id != null) {
                    StoryCluster ent = clusterById.get(target.id);
                    if (ent == null) {
                        ent = target.entity;
                    }
                    updateClusterEntity(ent, target.membersList);
                    clusters.save(ent);
                    target.entity = ent;
                } else {
                    // create new (shouldn't happen here)
                    createCluster(target.membersList, windowHours, titleThreshold, wireThreshold);
                }

                // remove candidate unclustered from processed
                unclusteredProcessed.removeAll(candidateUnclustered);
            } else {
                // only unclustered candidates -> create new cluster
                List<Item> group = new ArrayList<>(candidateUnclustered);
                group.add(x);
                // remove duplicates
                // compute representative
                StoryCluster created = createCluster(group, windowHours, titleThreshold, wireThreshold);
                State st = new State(created.getId(), created);
                st.membersList.addAll(group);
                states.add(st);
                clusterById.put(created.getId(), created);
                unclusteredProcessed.removeAll(candidateUnclustered);
            }
        }

        return allItems.size();
    }

    private boolean isEnabled() {
        return feedProperties.getCluster().isEnabled();
    }

    private boolean matches(Item a, Item b, int windowHours, double titleThreshold, double wireThreshold) {
        if (a.getSource() == null || b.getSource() == null) {
            return false;
        }
        if (a.getSource().getId() != null && a.getSource().getId().equals(b.getSource().getId())) {
            return false;
        }
        // use minutes for edge? spec hours; compare <= windowHours using duration
        if (Duration.between(a.getFetchedAt(), b.getFetchedAt()).abs().compareTo(Duration.ofHours(windowHours)) > 0) {
            return false;
        }
        double sim = jaccardSimilarity(normalizeTitle(a.getTitle()), normalizeTitle(b.getTitle()));
        if (sim >= titleThreshold) {
            return true;
        }
        String attrA = wireCredit(a.getSummary());
        String attrB = wireCredit(b.getSummary());
        if (attrA != null && attrA.equals(attrB) && sim >= wireThreshold) {
            return true;
        }
        return false;
    }

    private String ruleFor(Item a, Item b, double sim, double titleThreshold, double wireThreshold) {
        if (sim >= titleThreshold) {
            return "R1";
        }
        String attrA = wireCredit(a.getSummary());
        String attrB = wireCredit(b.getSummary());
        if (attrA != null && attrA.equals(attrB) && sim >= wireThreshold) {
            return "R2";
        }
        return "R1";
    }

    private StoryCluster createCluster(List<Item> group, int windowHours, double titleThreshold, double wireThreshold) {
        // representative = earliest fetched
        Item rep = group.get(0);
        for (Item it : group) {
            if (it.getFetchedAt().isBefore(rep.getFetchedAt()) ||
                    (it.getFetchedAt().equals(rep.getFetchedAt()) && it.getId() < rep.getId())) {
                rep = it;
            }
        }
        Instant first = group.get(0).getFetchedAt();
        Instant last = group.get(0).getFetchedAt();
        for (Item it : group) {
            if (it.getFetchedAt().isBefore(first)) {
                first = it.getFetchedAt();
            }
            if (it.getFetchedAt().isAfter(last)) {
                last = it.getFetchedAt();
            }
        }
        StoryCluster cluster = new StoryCluster();
        cluster.setRepresentativeItemId(rep.getId());
        cluster.setCreatedAt(clock.instant());
        cluster.setFirstSeenAt(first);
        cluster.setLastSeenAt(last);
        clusters.save(cluster);

        // insert members
        for (Item it : group) {
            // find best match among group
            String rule = "R1";
            double sim = 0.0;
            for (Item other : group) {
                if (other.getId().equals(it.getId())) {
                    continue;
                }
                double s = jaccardSimilarity(normalizeTitle(it.getTitle()), normalizeTitle(other.getTitle()));
                if (s >= titleThreshold || (wireCredit(it.getSummary()) != null
                        && wireCredit(it.getSummary()).equals(wireCredit(other.getSummary()))
                        && s >= wireThreshold)) {
                    sim = s;
                    rule = (s >= titleThreshold) ? "R1" : "R2";
                    break;
                }
            }
            StoryClusterMember sm = new StoryClusterMember();
            sm.setClusterId(cluster.getId());
            sm.setItemId(it.getId());
            sm.setMatchedRule(rule);
            sm.setSimilarity(sim);
            sm.setJoinedAt(clock.instant());
            members.save(sm);
        }
        return cluster;
    }

    private void updateClusterEntity(StoryCluster ent, List<Item> membersList) {
        // representative
        Item rep = membersList.get(0);
        for (Item it : membersList) {
            if (it.getFetchedAt().isBefore(rep.getFetchedAt()) ||
                    (it.getFetchedAt().equals(rep.getFetchedAt()) && it.getId() < rep.getId())) {
                rep = it;
            }
        }
        Instant first = membersList.get(0).getFetchedAt();
        Instant last = membersList.get(0).getFetchedAt();
        for (Item it : membersList) {
            if (it.getFetchedAt().isBefore(first)) {
                first = it.getFetchedAt();
            }
            if (it.getFetchedAt().isAfter(last)) {
                last = it.getFetchedAt();
            }
        }
        ent.setRepresentativeItemId(rep.getId());
        ent.setFirstSeenAt(first);
        ent.setLastSeenAt(last);
    }

    // ---------- API helpers ----------

    @Transactional(readOnly = true)
    public List<ClusterSummaryDto> listClusters(int sinceHours) {
        if (sinceHours <= 0) {
            sinceHours = 48;
        }
        if (sinceHours > 168) {
            sinceHours = 168;
        }
        Instant cutoff = clock.instant().minus(Duration.ofHours(sinceHours));
        List<StoryCluster> all = clusters.findAll();
        List<ClusterSummaryDto> out = new ArrayList<>();
        for (StoryCluster c : all) {
            if (c.getLastSeenAt().isBefore(cutoff)) {
                continue;
            }
            List<StoryClusterMember> ms = members.findByClusterId(c.getId());
            List<String> sourceNames = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (StoryClusterMember m : ms) {
                Item it = items.findById(m.getItemId()).orElse(null);
                if (it != null && it.getSource() != null) {
                    String name = it.getSource().getName();
                    if (name != null && seen.add(name)) {
                        sourceNames.add(name);
                    }
                }
            }
            sourceNames.sort(String::compareTo);
            Item repItem = items.findById(c.getRepresentativeItemId()).orElse(null);
            String repTitle = repItem == null ? null : repItem.getTitle();
            out.add(new ClusterSummaryDto(
                    c.getId(),
                    repTitle,
                    c.getRepresentativeItemId(),
                    ms.size(),
                    sourceNames,
                    c.getFirstSeenAt(),
                    c.getLastSeenAt()
            ));
        }
        out.sort((a, b) -> {
            int cmp = Integer.compare(b.memberCount(), a.memberCount());
            if (cmp != 0) {
                return cmp;
            }
            return b.lastSeenAt().compareTo(a.lastSeenAt());
        });
        return out;
    }

    @Transactional(readOnly = true)
    public ClusterDetailDto getCluster(Long id) {
        StoryCluster c = clusters.findById(id).orElse(null);
        if (c == null) {
            return null;
        }
        List<StoryClusterMember> ms = members.findByClusterId(c.getId());
        // order by (fetched_at, id)
        List<StoryClusterMember> ordered = new ArrayList<>(ms);
        ordered.sort((a, b) -> {
            Item ia = items.findById(a.getItemId()).orElse(null);
            Item ib = items.findById(b.getItemId()).orElse(null);
            Instant fa = ia == null ? Instant.MIN : ia.getFetchedAt();
            Instant fb = ib == null ? Instant.MIN : ib.getFetchedAt();
            int cmp = fa.compareTo(fb);
            if (cmp != 0) {
                return cmp;
            }
            return a.getItemId().compareTo(b.getItemId());
        });
        List<ClusterMemberDto> memberDtos = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (StoryClusterMember m : ordered) {
            Item it = items.findById(m.getItemId()).orElse(null);
            if (it == null) {
                continue;
            }
            String sourceName = it.getSource() == null ? null : it.getSource().getName();
            if (sourceName != null && seen.add(sourceName)) {
                sourceNames.add(sourceName);
            }
            memberDtos.add(new ClusterMemberDto(
                    it.getId(),
                    sourceName,
                    it.getTitle(),
                    it.getLink(),
                    it.getPublishedAt(),
                    it.getFetchedAt()
            ));
        }
        sourceNames.sort(String::compareTo);
        Item repItem = items.findById(c.getRepresentativeItemId()).orElse(null);
        String repTitle = repItem == null ? null : repItem.getTitle();
        return new ClusterDetailDto(
                c.getId(),
                repTitle,
                c.getRepresentativeItemId(),
                ms.size(),
                sourceNames,
                c.getFirstSeenAt(),
                c.getLastSeenAt(),
                memberDtos
        );
    }

}

