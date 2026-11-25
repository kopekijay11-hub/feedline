package ca.feedline.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "feed.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${feed.scheduling.pool-size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("feedline-sched-");
        return scheduler;
    }
}
