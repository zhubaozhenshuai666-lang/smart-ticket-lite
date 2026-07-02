package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.MqConsumerProperties;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;

final class RocketMqConsumerTuningSupport {

    private RocketMqConsumerTuningSupport() {
    }

    static void apply(DefaultMQPushConsumer consumer, MqConsumerProperties properties) {
        if (consumer == null || properties == null) {
            return;
        }
        consumer.setConsumeThreadMin(properties.getRocketMqConsumeThreadNumber());
        consumer.setConsumeThreadMax(properties.getRocketMqConsumeThreadMax());
        consumer.setPullBatchSize(properties.getRocketMqPullBatchSize());
        consumer.setConsumeMessageBatchMaxSize(properties.getRocketMqConsumeMessageBatchMaxSize());
        consumer.setPullThresholdForQueue(properties.getRocketMqPullThresholdForQueue());
    }
}
