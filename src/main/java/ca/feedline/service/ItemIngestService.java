package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

@Service
public class ItemIngestService {

    static final String TITLE_TOO_LONG = "TITLE_TOO_LONG";
    static final String NUL_BYTE = "NUL_BYTE";
    static final String DUPLICATE = "DUPLICATE";
    static final String DATA_INTEGRITY = "DATA_INTEGRITY";
    static final String VALIDATION = "VALIDATION";
    static final String GUID_ERROR = "GUID_ERROR";
    static final String INGEST_ERROR = "INGEST_ERROR";

    private final FeedNormalizer normalizer;
    private final ItemRepository items;
    private final FeedSourceRepository feedSources;

    public ItemIngestService(FeedNormalizer normalizer, ItemRepository items, FeedSourceRepository feedSources) {
        this.normalizer = normalizer;
        this.items = items;
        this.feedSources = feedSources;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItemIngestResult ingest(FeedSource source, SyndEntry entry, Instant fetchedAt) {
        String guid;
        try {
            guid = normalizer.guidFor(entry);
        } catch (RuntimeException e) {
            throw new ItemIngestFailure("UNKNOWN", GUID_ERROR, e);
        }

        try {
            FeedSource managedSource = feedSources.findById(source.getId()).orElse(source);
            Item item = normalizer.normalize(entry, managedSource, fetchedAt);
            if (items.findBySourceIdAndGuid(source.getId(), item.getGuid()).isPresent()) {
                return new ItemIngestResult(guid, false);
            }
            items.save(item);
            return new ItemIngestResult(guid, true);
        } catch (RuntimeException e) {
            String failureClass = classify(e);
            if (DUPLICATE.equals(failureClass)) {
                return new ItemIngestResult(guid, false);
            }
            throw new ItemIngestFailure(guid, failureClass, e);
        }
    }

    private String classify(RuntimeException failure) {
        String message = rootMessage(failure).toLowerCase(Locale.ROOT);
        if (message.contains("uq_items_source_guid")
                || (message.contains("duplicate key") && message.contains("violates unique constraint"))) {
            return DUPLICATE;
        }
        if (message.contains("ck_items_title_max_length")
                || message.contains("char_length(title)")
                || message.contains("value too long")) {
            return TITLE_TOO_LONG;
        }
        if (message.contains("0x00") || message.contains("\\u0000") || message.contains("nul byte")) {
            return NUL_BYTE;
        }
        if (failure instanceof DataIntegrityViolationException) {
            return DATA_INTEGRITY;
        }
        if (failure instanceof IllegalArgumentException) {
            return VALIDATION;
        }
        return INGEST_ERROR;
    }

    private String rootMessage(Throwable failure) {
        StringBuilder message = new StringBuilder();
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                message.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        return message.toString();
    }
}
