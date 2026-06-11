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
}
