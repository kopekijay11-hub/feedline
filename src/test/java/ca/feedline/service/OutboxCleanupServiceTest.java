package ca.feedline.service;

import ca.feedline.config.FeedProperties;
import ca.feedline.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class OutboxCleanupServiceTest {

    @Test
    void loopsAcrossBatchesUntilPartialBatch() {
        OutboxEventRepository repo = Mockito.mock(OutboxEventRepository.class);
        when(repo.deletePublishedBefore(any(), anyInt())).thenReturn(2, 2, 1);
        FeedProperties props = new FeedProperties();
        props.getOutbox().getCleanup().setBatchSize(2);
        OutboxCleanupService service = new OutboxCleanupService(repo, props, Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC));

        int deleted = service.cleanup();

        assertThat(deleted).isEqualTo(5);
        Mockito.verify(repo, Mockito.times(3)).deletePublishedBefore(any(), anyInt());
    }

    @Test
    void respectsEnabledFalse() {
        OutboxEventRepository repo = Mockito.mock(OutboxEventRepository.class);
        FeedProperties props = new FeedProperties();
        props.getOutbox().getCleanup().setEnabled(false);
        OutboxCleanupService service = new OutboxCleanupService(repo, props, Clock.systemUTC());

        int deleted = service.cleanup();

        assertThat(deleted).isEqualTo(0);
        Mockito.verifyNoInteractions(repo);
    }
}
