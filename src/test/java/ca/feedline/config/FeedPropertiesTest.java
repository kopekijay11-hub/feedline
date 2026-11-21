package ca.feedline.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeedPropertiesTest {

    @Test
    void outboxBatchSizeIsClampedToBoundedRange() {
        FeedProperties properties = new FeedProperties();

        properties.getOutbox().setBatchSize(0);
        assertThat(properties.getOutbox().getBatchSize()).isEqualTo(1);

        properties.getOutbox().setBatchSize(-5);
        assertThat(properties.getOutbox().getBatchSize()).isEqualTo(1);

        properties.getOutbox().setBatchSize(5000);
        assertThat(properties.getOutbox().getBatchSize()).isEqualTo(1000);

        properties.getOutbox().setBatchSize(50);
        assertThat(properties.getOutbox().getBatchSize()).isEqualTo(50);
    }

    @Test
    void outboxRetryBudgetIsClampedToSensibleRange() {
        FeedProperties properties = new FeedProperties();

        properties.getOutbox().setMaxAttempts(0);
        assertThat(properties.getOutbox().getMaxAttempts()).isEqualTo(1);

        properties.getOutbox().setMaxAttempts(1000);
        assertThat(properties.getOutbox().getMaxAttempts()).isEqualTo(100);
    }

    @Test
    void outboxBackoffAndSendTimeoutAreClamped() {
        FeedProperties properties = new FeedProperties();

        properties.getOutbox().setInitialBackoffMs(0);
        assertThat(properties.getOutbox().getInitialBackoffMs()).isEqualTo(1);

        properties.getOutbox().setInitialBackoffMs(1000);
        properties.getOutbox().setMaxBackoffMs(10);
        assertThat(properties.getOutbox().getMaxBackoffMs()).isEqualTo(1000);

        properties.getOutbox().setInitialBackoffMs(60_000);
        assertThat(properties.getOutbox().getMaxBackoffMs()).isEqualTo(60_000);

        properties.getOutbox().setInitialBackoffMs(1_000);
        properties.getOutbox().setMaxBackoffMs(9_000_000L);
        assertThat(properties.getOutbox().getMaxBackoffMs()).isEqualTo(3_600_000L);

        properties.getOutbox().setSendTimeoutMs(0);
        assertThat(properties.getOutbox().getSendTimeoutMs()).isEqualTo(1);

        properties.getOutbox().setSendTimeoutMs(600_000L);
        assertThat(properties.getOutbox().getSendTimeoutMs()).isEqualTo(60_000L);
    }

    @Test
    void outboxTopicAndPollDelayFallBackWhenBlank() {
        FeedProperties properties = new FeedProperties();

        properties.getOutbox().setTopic("  ");
        assertThat(properties.getOutbox().getTopic()).isEqualTo("feedline.items.ingested");

        properties.getOutbox().setPollDelay(null);
        assertThat(properties.getOutbox().getPollDelay()).isEqualTo("2000");
    }
}