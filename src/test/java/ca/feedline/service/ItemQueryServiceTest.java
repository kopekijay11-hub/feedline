package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemQueryServiceTest {

    private ItemQueryService service;
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        service = new ItemQueryService(entityManager);
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

    @Test
    void usesConfiguredDefaultAndMaxPageSizes() {
        FeedProperties.Items itemsConfig = new FeedProperties.Items();
        itemsConfig.setMaxPageSize(50);
        itemsConfig.setDefaultPageSize(10);
        ItemQueryService configured = new ItemQueryService(entityManager, itemsConfig);

        Query countQuery = mock(Query.class);
        Query pageQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(countQuery, pageQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);
        when(pageQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(pageQuery);
        when(pageQuery.getResultList()).thenReturn(java.util.List.of());

        configured.list(null, null, null, null, null, null, null, 0);
        verify(pageQuery).setParameter("limit", 11);

        when(entityManager.createNativeQuery(anyString())).thenReturn(countQuery, pageQuery);
        configured.list(null, null, null, null, null, null, null, 200);
        verify(pageQuery).setParameter("limit", 51);
    }

    @Test
    void usesConfiguredSearchLanguage() {
        FeedProperties.Items itemsConfig = new FeedProperties.Items();
        itemsConfig.setSearchLanguage("simple");
        ItemQueryService configured = new ItemQueryService(entityManager, itemsConfig);

        Query countQuery = mock(Query.class);
        Query pageQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(countQuery, pageQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);
        when(pageQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(pageQuery);
        when(pageQuery.getResultList()).thenReturn(java.util.List.of());

        configured.list(null, "hello", null, null, null, null, null, 20);

        verify(countQuery).setParameter("q", "hello");
        verify(countQuery).setParameter("searchLanguage", "simple");
        verify(pageQuery).setParameter("q", "hello");
        verify(pageQuery).setParameter("searchLanguage", "simple");
    }

    @Test
    void nullItemsConfigFallsBackToDefaults() {
        ItemQueryService configured = new ItemQueryService(entityManager, (FeedProperties.Items) null);

        Query countQuery = mock(Query.class);
        Query pageQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(countQuery, pageQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);
        when(pageQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(pageQuery);
        when(pageQuery.getResultList()).thenReturn(java.util.List.of());

        configured.list(null, null, null, null, null, null, null, 0);
        verify(pageQuery).setParameter("limit", 21);
        assertThat(configured).isNotNull();
    }
}
