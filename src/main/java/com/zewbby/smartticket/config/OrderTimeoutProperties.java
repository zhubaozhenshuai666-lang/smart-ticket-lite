package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.order-timeout")
public class OrderTimeoutProperties {

    public static final String PUBLISHER_MODE_OUTBOX = "outbox";

    public static final String PUBLISHER_MODE_KAFKA = "kafka";

    private boolean delayMessageEnabled = false;

    private String publisherMode = PUBLISHER_MODE_KAFKA;

    private int scanBatchSize = 1000;

    private long scanFixedDelayMillis = 1000L;

    private String kafkaOrderTimeoutTopic = "smart-ticket.order.timeout";

    private String kafkaOrderTimeoutConsumerGroup = "smart-ticket-order-timeout";

    public boolean isDelayMessageEnabled() {
        return delayMessageEnabled;
    }

    public void setDelayMessageEnabled(boolean delayMessageEnabled) {
        this.delayMessageEnabled = delayMessageEnabled;
    }

    public String getPublisherMode() {
        return publisherMode == null || publisherMode.isBlank()
                ? PUBLISHER_MODE_KAFKA
                : publisherMode.trim();
    }

    public void setPublisherMode(String publisherMode) {
        this.publisherMode = publisherMode;
    }

    public boolean isOutboxPublisherMode() {
        return PUBLISHER_MODE_OUTBOX.equalsIgnoreCase(getPublisherMode());
    }

    public boolean isKafkaPublisherMode() {
        return PUBLISHER_MODE_KAFKA.equalsIgnoreCase(getPublisherMode());
    }

    public int getScanBatchSize() {
        return Math.max(1, scanBatchSize);
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }

    public long getScanFixedDelayMillis() {
        return Math.max(100L, scanFixedDelayMillis);
    }

    public void setScanFixedDelayMillis(long scanFixedDelayMillis) {
        this.scanFixedDelayMillis = scanFixedDelayMillis;
    }

    public String getKafkaOrderTimeoutTopic() {
        return kafkaOrderTimeoutTopic == null || kafkaOrderTimeoutTopic.isBlank()
                ? "smart-ticket.order.timeout"
                : kafkaOrderTimeoutTopic.trim();
    }

    public void setKafkaOrderTimeoutTopic(String kafkaOrderTimeoutTopic) {
        this.kafkaOrderTimeoutTopic = kafkaOrderTimeoutTopic;
    }

    public String getKafkaOrderTimeoutConsumerGroup() {
        return kafkaOrderTimeoutConsumerGroup == null || kafkaOrderTimeoutConsumerGroup.isBlank()
                ? "smart-ticket-order-timeout"
                : kafkaOrderTimeoutConsumerGroup.trim();
    }

    public void setKafkaOrderTimeoutConsumerGroup(String kafkaOrderTimeoutConsumerGroup) {
        this.kafkaOrderTimeoutConsumerGroup = kafkaOrderTimeoutConsumerGroup;
    }
}
