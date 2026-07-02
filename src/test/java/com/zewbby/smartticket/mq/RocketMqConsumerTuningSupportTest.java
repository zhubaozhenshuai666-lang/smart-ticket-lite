package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.MqConsumerProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RocketMqConsumerTuningSupportTest {

    @Test
    void appliesConfiguredRocketMqConsumerSettings() {
        MqConsumerProperties properties = new MqConsumerProperties();
        properties.setRocketMqConsumeThreadNumber(12);
        properties.setRocketMqConsumeThreadMax(48);
        properties.setRocketMqPullBatchSize(128);
        properties.setRocketMqConsumeMessageBatchMaxSize(32);
        properties.setRocketMqPullThresholdForQueue(2048);
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("test-group");

        RocketMqConsumerTuningSupport.apply(consumer, properties);

        assertThat(consumer.getConsumeThreadMin()).isEqualTo(12);
        assertThat(consumer.getConsumeThreadMax()).isEqualTo(48);
        assertThat(consumer.getPullBatchSize()).isEqualTo(128);
        assertThat(consumer.getConsumeMessageBatchMaxSize()).isEqualTo(32);
        assertThat(consumer.getPullThresholdForQueue()).isEqualTo(2048);
    }
}
