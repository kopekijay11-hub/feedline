package ca.feedline.service;

import ca.feedline.domain.FeedSource;
import ca.feedline.domain.Item;
import ca.feedline.repository.FeedSourceRepository;
import ca.feedline.repository.ItemRepository;
import com.rometools.rome.feed.synd.SyndEntry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
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
    private final TransactionTemplate txTemplate;

    public ItemIngestService(FeedNormalizer normalizer, ItemRepository items, FeedSourceRepository feedSources,
                             PlatformTransactionManager transactionManager) {
        this.normalizer = normalizer;
        this.items = items;
        this.feedSources = feedSources;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ItemIngestResult ingest(FeedSource source, SyndEntry entry, Instant fetchedAt) {
        String guid;
        try {
            guid = normalizer.guidFor(entry);
        } catch (RuntimeException e) {
            throw new ItemIngestFailure("UNKNOWN", GUID_ERROR, e);
        }

        try {
            return txTemplate.execute(status -> {
                FeedSource managedSource = feedSources.findById(source.getId()).orElse(source);
                Item item = normalizer.normalize(entry, managedSource, fetchedAt);
                if (items.findBySourceIdAndGuid(source.getId(), item.getGuid()).isPresent()) {
                    return new ItemIngestResult(guid, false);
                }
                items.saveAndFlush(item);
                return new ItemIngestResult(guid, true);
            });
        } catch (RuntimeException e) {
            String failureClass = classify(e);
            if (DUPLICATE.equals(failureClass)) {
                return new ItemIngestResult(guid, false);
            }
            throw new ItemIngestFailure(guid, failureClass, e);
        }
    }

    private String classify(RuntimeException failure) {
        SqlSignature sql = firstSqlSignature(failure);
        if (sql != null) {
            if ("23505".equals(sql.sqlState())) {
                return DUPLICATE;
            }
            if ("23514".equals(sql.sqlState()) && sql.constraintName() != null
                    && sql.constraintName().toLowerCase(Locale.ROOT).contains("title")) {
                return TITLE_TOO_LONG;
            }
            if (containsNulByte(sql.message())) {
                return NUL_BYTE;
            }
        }

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
        if (containsNulByte(message)) {
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

    private static SqlSignature firstSqlSignature(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof org.hibernate.exception.ConstraintViolationException violation) {
                SQLException sql = violation.getSQLException();
                return new SqlSignature(sql.getSQLState(), violation.getConstraintName(), sql.getMessage());
            }
            if (cursor instanceof SQLException sql) {
                return new SqlSignature(sql.getSQLState(), null, sql.getMessage());
            }
            cursor = cursor.getCause();
        }
        return null;
    }

    private static boolean containsNulByte(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("0x00") || lower.contains("\\u0000") || lower.contains("nul byte");
    }

    private static String rootMessage(Throwable failure) {
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

    private record SqlSignature(String sqlState, String constraintName, String message) {
    }
}
