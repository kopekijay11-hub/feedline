package ca.feedline.metrics;

import ca.feedline.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.time.Clock;
import java.util.Optional;


@SuppressFBWarnings(value = "NM_SAME_SIMPLE_NAME_AS_SUPERCLASS", justification = "alias")
public class FeedlineMetrics extends ca.feedline.service.FeedlineMetrics {
    public FeedlineMetrics(MeterRegistry registry) { super(registry); }
    public FeedlineMetrics(MeterRegistry registry, OutboxEventRepository repository) { super(registry, repository); }
    public FeedlineMetrics(MeterRegistry registry, OutboxEventRepository repository, Clock clock) { super(registry, repository, clock); }
    public FeedlineMetrics(Optional<MeterRegistry> registry, OutboxEventRepository repository, Clock clock) { super(registry, repository, clock); }
}
