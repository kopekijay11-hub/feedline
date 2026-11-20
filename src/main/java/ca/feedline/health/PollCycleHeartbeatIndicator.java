package ca.feedline.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component("pollCycleHeartbeat")
public class PollCycleHeartbeatIndicator implements HealthIndicator {

    private final SourceHealthService service;

    public PollCycleHeartbeatIndicator(SourceHealthService service) {
        this.service = service;
    }

    @Override
    public Health health() {
        Instant lastCompleted = service.lastCompletedPollCycle();
        Health.Builder builder = service.heartbeatStale() ? Health.down() : Health.up();
        if (lastCompleted != null) {
            builder.withDetail("lastCompletedPollCycle", lastCompleted);
        }
        return builder
                .withDetail("schedulingEnabled", service.schedulingEnabled())
                .withDetail("pollIntervalSeconds", service.pollInterval().toSeconds())
                .build();
    }
}
