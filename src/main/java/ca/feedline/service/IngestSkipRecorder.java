package ca.feedline.service;

import ca.feedline.domain.IngestSkip;
import ca.feedline.domain.IngestSkipId;
import ca.feedline.repository.IngestSkipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class IngestSkipRecorder {

    private final IngestSkipRepository skips;

    public IngestSkipRecorder(IngestSkipRepository skips) {
        this.skips = skips;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long sourceId, String guid, String failureClass, Instant skippedAt) {
        IngestSkipId id = new IngestSkipId(sourceId, guid);
        skips.findById(id).ifPresentOrElse(
                existing -> {
                    existing.setFailureClass(failureClass);
                    existing.setLastSkippedAt(skippedAt);
                    existing.setOccurrences(existing.getOccurrences() + 1);
                    skips.save(existing);
                },
                () -> skips.save(new IngestSkip(id, failureClass, skippedAt)));
    }
}
