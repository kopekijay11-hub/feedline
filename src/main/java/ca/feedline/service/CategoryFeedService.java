package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class CategoryFeedService {

    private final FeedSourceRepository feedSourceRepository;
    private final ItemRepository itemRepository;
    private final ObjectMapper objectMapper;

    public CategoryFeedService(FeedSourceRepository feedSourceRepository,
                               ItemRepository itemRepository,
                               ObjectMapper objectMapper) {
        this.feedSourceRepository = feedSourceRepository;
        this.itemRepository = itemRepository;
        this.objectMapper = objectMapper;
    }

    public List<CategoryFeed> categories() {
        Map<String, String> mapping = loadCategories();
        Map<String, CategoryFeed> bySlug = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String sourceName = entry.getKey();
            String categoryName = entry.getValue();
            String slug = slugify(categoryName);
            CategoryFeed existing = bySlug.get(slug);
            List<Long> sourceIds = existing == null ? new ArrayList<>() : new ArrayList<>(existing.sourceIds());
            Optional<FeedSource> source = feedSourceRepository.findByName(sourceName);
            if (source.isPresent() && source.get().getId() != null) {
                Long id = source.get().getId();
                if (!sourceIds.contains(id)) {
                    sourceIds.add(id);
                }
            } else {
                // Unresolved names are skipped and logged; never fatal.
                System.getLogger(CategoryFeedService.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "categories.json source name does not resolve to a feed source: " + sourceName);
            }
            bySlug.put(slug, new CategoryFeed(slug, categoryName, sourceIds));
        }
        return new ArrayList<>(bySlug.values());
    }

    public Optional<CategoryFeed> bySlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        for (CategoryFeed category : categories()) {
            if (category.slug().equals(slug)) {
                return Optional.of(category);
            }
        }
        return Optional.empty();
    }

    public List<Long> allSourceIds() {
        return feedSourceRepository.findAll().stream()
                .map(FeedSource::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FeedItem> newestItems(List<Long> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return List.of();
        }
        return itemRepository.findNewestBySourceIds(sourceIds, PageRequest.of(0, 50)).stream()
                .map(item -> new FeedItem(
                        item.getId(),
                        item.getTitle(),
                        item.getLink(),
                        item.getSummary(),
                        item.getPublishedAt(),
                        item.getFetchedAt(),
                        item.getSource().getName(),
                        item.getSource().getUrl()))
                .toList();
    }

    private Map<String, String> loadCategories() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("static/categories.json")) {
            if (is == null) {
                return Map.of();
            }
            return objectMapper.readValue(is, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("unable to read static/categories.json", e);
        }
    }

    private String slugify(String category) {
        return category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "").replaceAll("-+$", "");
    }

    public record CategoryFeed(String slug, String name, List<Long> sourceIds) {
        public CategoryFeed {
            sourceIds = List.copyOf(sourceIds == null ? List.of() : sourceIds);
        }
    }

    public record FeedItem(Long id, String title, String link, String summary,
                           Instant publishedAt, Instant fetchedAt,
                           String sourceName, String sourceUrl) {
    }
}
