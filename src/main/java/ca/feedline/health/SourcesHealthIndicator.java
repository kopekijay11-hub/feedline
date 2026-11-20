package ca.feedline.health;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("sources")
@ConditionalOnEnabledHealthIndicator("sources")
public class SourcesHealthIndicator implements HealthIndicator {

    private final SourceHealthService service;

    public SourcesHealthIndicator(SourceHealthService service) {
        this.service = service;
    }

    @Override
    public Health health() {
        SourcesHealth h = service.aggregate();
        Health.Builder b = "UP".equals(h.sourcesStatus()) ? Health.up() : Health.down();
        b.withDetail("lastCompletedPollCycle", h.lastCompletedPollCycle());
        b.withDetail("heartbeatStale", h.heartbeatStale());
        b.withDetail("sourceCount", h.sources().size());
        return b.build();
    }
}
