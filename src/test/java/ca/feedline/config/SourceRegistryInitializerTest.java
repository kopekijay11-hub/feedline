package ca.feedline.config;

import ca.feedline.domain.FeedSource;
import ca.feedline.repository.FeedSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceRegistryInitializerTest {

    @Test
    void existingSourceMetadataIsSyncedWithConfiguration() {
        FeedProperties.Source configured = new FeedProperties.Source("New name", "http://example.test/feed");
        configured.setEnabled(false);
        FeedProperties props = new FeedProperties();
        props.setSources(List.of(configured));

        FeedSource existing = new FeedSource("Old name", "http://example.test/feed");
        existing.setId(7L);
        existing.setEnabled(true);
        FeedSourceRepository repo = mock(FeedSourceRepository.class);
        when(repo.findByUrl("http://example.test/feed")).thenReturn(Optional.of(existing));

        new SourceRegistryInitializer(props, repo).run(mock(ApplicationArguments.class));

        verify(repo).save(existing);
        assertThat(existing.getName()).isEqualTo("New name");
        assertThat(existing.isEnabled()).isFalse();
    }
}
