package ca.feedline.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(SchedulingConfig.class);

    @Test
    void providesSingleThreadPoolTaskSchedulerWithDefaultPoolAndPrefix() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
            assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1);

            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("feedline-sched-");
        });
    }
}
