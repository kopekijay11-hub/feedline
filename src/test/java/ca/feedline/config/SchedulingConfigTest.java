package ca.feedline.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void waitsForInFlightTasksToFinishOnShutdown() {
        contextRunner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean completedNormally = new AtomicBoolean(false);
            AtomicBoolean interrupted = new AtomicBoolean(false);

            scheduler.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(1500);
                    completedNormally.set(true);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            });

            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for scheduled task to start", e);
            }

            scheduler.shutdown();

            assertThat(completedNormally).isTrue();
            assertThat(interrupted).isFalse();
        });
    }
}
