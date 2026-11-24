package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.domain.Item;
import ca.feedline.dto.ItemDto;
import ca.feedline.dto.ItemsPage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ItemQueryService {

    private final EntityManager entityManager;
    private final FeedProperties.Items itemsConfig;

    ItemQueryService(EntityManager entityManager) {
        this(entityManager, new FeedProperties().getItems());
    }

    @Autowired
    public ItemQueryService(EntityManager entityManager, FeedProperties feedProperties) {
        this(entityManager, feedProperties == null ? new FeedProperties().getItems() : feedProperties.getItems());
    }

    ItemQueryService(EntityManager entityManager, FeedProperties.Items itemsConfig) {
        this.entityManager = entityManager;
        this.itemsConfig = itemsConfig == null ? new FeedProperties().getItems() : itemsConfig;
    }

    public ItemsPage list(Long source, String q, Instant from, Instant to, Instant fetchedFrom,
                          Instant fetchedTo, String cursor, int requestedSize) {
        return list(source, (java.util.List<Long>) null, q, from, to, fetchedFrom, fetchedTo, cursor, requestedSize);
    }

    public ItemsPage list(Long source, java.util.List<Long> sources, String q, Instant from, Instant to,
                          Instant fetchedFrom, Instant fetchedTo, String cursor, int requestedSize) {
        return listInternal(source, sources, q, from, to, fetchedFrom, fetchedTo, cursor, requestedSize);
    }

    public ItemsPage list(Long source, String sources, String q, Instant from, Instant to, Instant fetchedFrom,
                          Instant fetchedTo, String cursor, int requestedSize) {
        java.util.List<Long> parsed = null;
        if (sources != null && !sources.isBlank()) {
            parsed = new java.util.ArrayList<>();
            for (String part : sources.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    parsed.add(Long.valueOf(t));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("invalid source id: " + t);
                }
            }
            if (parsed.isEmpty()) {
                parsed = null;
            }
        }
        return list(source, parsed, q, from, to, fetchedFrom, fetchedTo, cursor, requestedSize);
    }

    private ItemsPage listInternal(Long source, java.util.List<Long> sources, String q, Instant from, Instant to,
                                   Instant fetchedFrom, Instant fetchedTo, String cursor, int requestedSize) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        if (fetchedFrom != null && fetchedTo != null && fetchedFrom.isAfter(fetchedTo)) {
            throw new IllegalArgumentException("fetchedFrom must not be after fetchedTo");
        }
        java.util.List<Long> effective = new java.util.ArrayList<>();
        boolean hasSource = source != null;
        boolean hasSources = sources != null && !sources.isEmpty();
        if (hasSource && hasSources) {
            throw new IllegalArgumentException("source and sources are mutually exclusive");
        }
        if (hasSource) {
            effective.add(source);
        }
        if (hasSources) {
            for (Long s : sources) {
                if (s != null) {
                    effective.add(s);
                }
            }
        }
        if (effective.size() > 50) {
            throw new IllegalArgumentException("too many source ids (max 50)");
        }
        int size = requestedSize <= 0 ? itemsConfig.getDefaultPageSize() : Math.min(requestedSize, itemsConfig.getMaxPageSize());
        Cursor c = decodeCursor(cursor);

        StringBuilder where = new StringBuilder("WHERE 1=1");
        if (!effective.isEmpty()) {
            where.append(" AND source_id IN (:sources)");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND search_vector @@ plainto_tsquery(CAST(:searchLanguage AS regconfig), :q)");
        }
        if (from != null) {
            where.append(" AND published_at >= :from");
        }
        if (to != null) {
            where.append(" AND published_at <= :to");
        }
        if (fetchedFrom != null) {
            where.append(" AND fetched_at >= :fetchedFrom");
        }
        if (fetchedTo != null) {
            where.append(" AND fetched_at <= :fetchedTo");
        }
        // null policy: a date range excludes rows with null published_at automatically,
        // because the comparisons above are UNKNOWN for null. No extra predicate needed.

        // total count (filters only, no cursor)
        String countSql = "SELECT COUNT(*) FROM items " + where;
        Query countQuery = entityManager.createNativeQuery(countSql);
        bindParams(countQuery, effective, q, from, to, fetchedFrom, fetchedTo, null);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        // page query
        StringBuilder pageSql = new StringBuilder(
                "SELECT id, source_id, guid, link, title, summary, published_at, fetched_at FROM items "
                        + where);
        if (c != null) {
            pageSql.append(" AND (fetched_at < :cf OR (fetched_at = :cf AND id < :ci))");
        }
        pageSql.append(" ORDER BY fetched_at DESC, id DESC LIMIT :limit");
        Query pageQuery = entityManager.createNativeQuery(pageSql.toString());
        bindParams(pageQuery, effective, q, from, to, fetchedFrom, fetchedTo, c);
        if (c != null) {
            pageQuery.setParameter("cf", c.fetchedAt());
            pageQuery.setParameter("ci", c.id());
        }
        pageQuery.setParameter("limit", size + 1);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = pageQuery.getResultList();
        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, size));
        }
        List<ItemDto> items = new ArrayList<>();
        for (Object[] r : rows) {
            items.add(new ItemDto(
                    ((Number) r[0]).longValue(),
                    ((Number) r[1]).longValue(),
                    (String) r[2],
                    (String) r[3],
                    (String) r[4],
                    (String) r[5],
                    toInstant(r[6]),
                    toInstant(r[7])
            ));
        }
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            ItemDto last = items.get(items.size() - 1);
            nextCursor = encodeCursor(last.fetchedAt(), last.id());
        }
        return new ItemsPage(items, nextCursor, total, hasMore);
    }

    public ItemDto findById(Long id) {
        Item item = entityManager.find(Item.class, id);
        if (item == null) {
            return null;
        }
        return new ItemDto(
                item.getId(),
                item.getSource().getId(),
                item.getGuid(),
                item.getLink(),
                item.getTitle(),
                item.getSummary(),
                item.getPublishedAt(),
                item.getFetchedAt()
        );
    }

    private void bindParams(Query q, java.util.List<Long> sources, String text, Instant from, Instant to,
                            Instant fetchedFrom, Instant fetchedTo, Cursor cursor) {
        if (sources != null && !sources.isEmpty()) {
            q.setParameter("sources", sources);
        }
        if (text != null && !text.isBlank()) {
            q.setParameter("q", text);
            q.setParameter("searchLanguage", itemsConfig.getSearchLanguage());
        }
        if (from != null) {
            q.setParameter("from", java.sql.Timestamp.from(from));
        }
        if (to != null) {
            q.setParameter("to", java.sql.Timestamp.from(to));
        }
        if (fetchedFrom != null) {
            q.setParameter("fetchedFrom", java.sql.Timestamp.from(fetchedFrom));
        }
        if (fetchedTo != null) {
            q.setParameter("fetchedTo", java.sql.Timestamp.from(fetchedTo));
        }
    }

    private Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return Instant.parse(o.toString());
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("bad cursor");
            }
            Instant ts = Instant.parse(parts[0]);
            long id = Long.parseLong(parts[1]);
            return new Cursor(ts, id);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IllegalArgumentException("malformed cursor");
        }
    }

    private String encodeCursor(Instant fetchedAt, Long id) {
        String raw = fetchedAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Cursor(Instant fetchedAt, long id) {
    }
}
