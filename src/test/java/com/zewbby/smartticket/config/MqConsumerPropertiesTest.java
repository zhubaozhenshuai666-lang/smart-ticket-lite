package com.zewbby.smartticket.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MqConsumerPropertiesTest {

    @Test
    void defaultsPreferShardedAsyncOrderQueues() {
        MqConsumerProperties properties = new MqConsumerProperties();

        assertThat(properties.getAsyncQueueShardCount()).isEqualTo(16);
        assertThat(properties.getConcurrentConsumers()).isEqualTo(8);
        assertThat(properties.getMaxConcurrentConsumers()).isEqualTo(64);
        assertThat(properties.getPrefetchCount()).isEqualTo(10);
    }

    @Test
    void maxConcurrentConsumersCannotBeLowerThanInitialConsumers() {
        MqConsumerProperties properties = new MqConsumerProperties();
        properties.setConcurrentConsumers(32);
        properties.setMaxConcurrentConsumers(8);

        assertThat(properties.getMaxConcurrentConsumers()).isEqualTo(32);
    }

    @Test
    void consumerConcurrencyAndShardCountAreCapped() {
        MqConsumerProperties properties = new MqConsumerProperties();
        properties.setMaxConcurrentConsumerCap(64);
        properties.setConcurrentConsumers(128);
        properties.setMaxConcurrentConsumers(256);
        properties.setMaxAsyncQueueShardCount(32);
        properties.setAsyncQueueShardCount(128);

        assertThat(properties.getConcurrentConsumers()).isEqualTo(64);
        assertThat(properties.getMaxConcurrentConsumers()).isEqualTo(64);
        assertThat(properties.getAsyncQueueShardCount()).isEqualTo(32);
    }

    @Test
    void prefetchCountIsBoundedByMaxPrefetchAndUnackedBudget() {
        MqConsumerProperties properties = new MqConsumerProperties();
        properties.setConcurrentConsumers(20);
        properties.setMaxConcurrentConsumers(50);
        properties.setPrefetchCount(100);
        properties.setMaxPrefetchCount(30);
        properties.setMaxUnackedMessages(500);

        assertThat(properties.getPrefetchCount()).isEqualTo(10);
    }

    @Test
    void rocketMqConsumerSettingsAreBounded() {
        MqConsumerProperties properties = new MqConsumerProperties();
        properties.setMaxConcurrentConsumerCap(64);
        properties.setRocketMqConsumeThreadNumber(128);
        properties.setRocketMqConsumeThreadMax(256);
        properties.setRocketMqPullBatchSize(1000);
        properties.setRocketMqConsumeMessageBatchMaxSize(1000);
        properties.setRocketMqPullThresholdForQueue(100000);

        assertThat(properties.getRocketMqConsumeThreadNumber()).isEqualTo(64);
        assertThat(properties.getRocketMqConsumeThreadMax()).isEqualTo(64);
        assertThat(properties.getRocketMqPullBatchSize()).isEqualTo(256);
        assertThat(properties.getRocketMqConsumeMessageBatchMaxSize()).isEqualTo(64);
        assertThat(properties.getRocketMqPullThresholdForQueue()).isEqualTo(10_000);
    }
}
