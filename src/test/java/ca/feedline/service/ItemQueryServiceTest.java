package ca.feedline.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ItemQueryServiceTest {

    private ItemQueryService service;

    @BeforeEach
    void setUp() {
        service = new ItemQueryService(mock(EntityManager.class));
    }

    @Test
    void rejectsInvertedPublishedRange() {
        Instant from = Instant.parse("2025-12-31T00:00:00Z");
        Instant to = Instant.parse("2025-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.list(null, null, from, to, null, null, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must not be after to");
    }

    @Test
    void rejectsInvertedFetchedRange() {
        Instant fetchedFrom = Instant.parse("2025-12-31T00:00:00Z");
        Instant fetchedTo = Instant.parse("2025-01-01T00:00:00Z");

        assertThatThrownBy(() -> service.list(null, null, null, null, fetchedFrom, fetchedTo, null, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fetchedFrom must not be after fetchedTo");
    }
}
