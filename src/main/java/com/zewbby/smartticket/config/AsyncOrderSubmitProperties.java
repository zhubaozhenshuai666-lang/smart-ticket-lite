package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.async-order-submit")
public class AsyncOrderSubmitProperties {

    public static final String PUBLISHER_MODE_OUTBOX = "outbox";

    public static final String PUBLISHER_MODE_REDIS_STREAM = "redis-stream";

    public static final String PUBLISHER_MODE_KAFKA = "kafka";

    /**
     * 是否在入口发布消息前先写 ticket_order_request。
     *
     * 默认开启，保持可靠 Outbox 兼容链路；高并发活动可以关闭，让消费者根据消息补建请求记录，
     * 从入口链路中移除一次 MySQL insert。
     */
    private boolean persistRequestBeforePublish = true;

    /**
     * 异步下单消息发布模式。
     *
     * outbox: 写 local_message 后由本地消息发送器可靠投递 Kafka，可靠性强但 DB 写放大明显。
     * redis-stream: 历史本地事件流模式，不作为抢票主链路。
     * kafka: 入口写 Kafka topic，由 Kafka 消费者复用异步创单处理器创建正式订单。
     */
    private String publisherMode = PUBLISHER_MODE_OUTBOX;

    /**
     * 是否启用异步下单 in-flight 退避。
     *
     * 它限制“Redis 已准备进入预扣/MQ，但消费者还没处理到终态”的请求数，防止热点票档把 MQ、消费者和 MySQL
     * 堆到不可恢复的延迟区间。
     */
    private boolean inFlightControlEnabled = true;

    private long maxInFlightPerTicketCategory = 20000L;

    private long inFlightCounterTtlSeconds = 600L;

    private String redisStreamName = "stream:order:async:create";

    private String redisStreamConsumerGroup = "order-create-consumers";

    private String redisStreamConsumerName = "order-create-consumer";

    private int redisStreamReadBatchSize = 64;

    private long redisStreamPollFixedDelayMillis = 50L;

    private long redisStreamBlockMillis = 100L;

    private int redisStreamWorkerThreads = 1;

    private String kafkaAsyncCreateOrderTopic = "smart-ticket.async-order.create";

    private String kafkaAsyncCreateOrderDeadLetterTopic = "smart-ticket.async-order.create.DLT";

    private String kafkaAsyncCreateOrderConsumerGroup = "smart-ticket-async-order-create";

    public boolean isPersistRequestBeforePublish() {
        return persistRequestBeforePublish;
    }

    public void setPersistRequestBeforePublish(boolean persistRequestBeforePublish) {
        this.persistRequestBeforePublish = persistRequestBeforePublish;
    }

    public String getPublisherMode() {
        return publisherMode == null ? PUBLISHER_MODE_OUTBOX : publisherMode.trim();
    }

    public void setPublisherMode(String publisherMode) {
        this.publisherMode = publisherMode;
    }

    public boolean isRedisStreamPublisherMode() {
        return PUBLISHER_MODE_REDIS_STREAM.equalsIgnoreCase(getPublisherMode());
    }

    public boolean isKafkaPublisherMode() {
        return PUBLISHER_MODE_KAFKA.equalsIgnoreCase(getPublisherMode());
    }

    public boolean isInFlightControlEnabled() {
        return inFlightControlEnabled;
    }

    public void setInFlightControlEnabled(boolean inFlightControlEnabled) {
        this.inFlightControlEnabled = inFlightControlEnabled;
    }

    public long getMaxInFlightPerTicketCategory() {
        return Math.max(1L, maxInFlightPerTicketCategory);
    }

    public void setMaxInFlightPerTicketCategory(long maxInFlightPerTicketCategory) {
        this.maxInFlightPerTicketCategory = maxInFlightPerTicketCategory;
    }

    public long getInFlightCounterTtlSeconds() {
        return Math.max(30L, inFlightCounterTtlSeconds);
    }

    public void setInFlightCounterTtlSeconds(long inFlightCounterTtlSeconds) {
        this.inFlightCounterTtlSeconds = inFlightCounterTtlSeconds;
    }

    public String getRedisStreamName() {
        return redisStreamName == null || redisStreamName.isBlank()
                ? "stream:order:async:create"
                : redisStreamName.trim();
    }

    public void setRedisStreamName(String redisStreamName) {
        this.redisStreamName = redisStreamName;
    }

    public String getRedisStreamConsumerGroup() {
        return redisStreamConsumerGroup == null || redisStreamConsumerGroup.isBlank()
                ? "order-create-consumers"
                : redisStreamConsumerGroup.trim();
    }

    public void setRedisStreamConsumerGroup(String redisStreamConsumerGroup) {
        this.redisStreamConsumerGroup = redisStreamConsumerGroup;
    }

    public String getRedisStreamConsumerName() {
        return redisStreamConsumerName == null || redisStreamConsumerName.isBlank()
                ? "order-create-consumer"
                : redisStreamConsumerName.trim();
    }

    public void setRedisStreamConsumerName(String redisStreamConsumerName) {
        this.redisStreamConsumerName = redisStreamConsumerName;
    }

    public int getRedisStreamReadBatchSize() {
        return Math.max(1, redisStreamReadBatchSize);
    }

    public void setRedisStreamReadBatchSize(int redisStreamReadBatchSize) {
        this.redisStreamReadBatchSize = redisStreamReadBatchSize;
    }

    public long getRedisStreamPollFixedDelayMillis() {
        return Math.max(10L, redisStreamPollFixedDelayMillis);
    }

    public void setRedisStreamPollFixedDelayMillis(long redisStreamPollFixedDelayMillis) {
        this.redisStreamPollFixedDelayMillis = redisStreamPollFixedDelayMillis;
    }

    public long getRedisStreamBlockMillis() {
        return Math.max(1L, redisStreamBlockMillis);
    }

    public void setRedisStreamBlockMillis(long redisStreamBlockMillis) {
        this.redisStreamBlockMillis = redisStreamBlockMillis;
    }

    public int getRedisStreamWorkerThreads() {
        return Math.max(1, redisStreamWorkerThreads);
    }

    public void setRedisStreamWorkerThreads(int redisStreamWorkerThreads) {
        this.redisStreamWorkerThreads = redisStreamWorkerThreads;
    }

    public String getKafkaAsyncCreateOrderTopic() {
        return kafkaAsyncCreateOrderTopic == null || kafkaAsyncCreateOrderTopic.isBlank()
                ? "smart-ticket.async-order.create"
                : kafkaAsyncCreateOrderTopic.trim();
    }

    public void setKafkaAsyncCreateOrderTopic(String kafkaAsyncCreateOrderTopic) {
        this.kafkaAsyncCreateOrderTopic = kafkaAsyncCreateOrderTopic;
    }

    public String getKafkaAsyncCreateOrderDeadLetterTopic() {
        return kafkaAsyncCreateOrderDeadLetterTopic == null || kafkaAsyncCreateOrderDeadLetterTopic.isBlank()
                ? getKafkaAsyncCreateOrderTopic() + ".DLT"
                : kafkaAsyncCreateOrderDeadLetterTopic.trim();
    }

    public void setKafkaAsyncCreateOrderDeadLetterTopic(String kafkaAsyncCreateOrderDeadLetterTopic) {
        this.kafkaAsyncCreateOrderDeadLetterTopic = kafkaAsyncCreateOrderDeadLetterTopic;
    }

    public String getKafkaAsyncCreateOrderConsumerGroup() {
        return kafkaAsyncCreateOrderConsumerGroup == null || kafkaAsyncCreateOrderConsumerGroup.isBlank()
                ? "smart-ticket-async-order-create"
                : kafkaAsyncCreateOrderConsumerGroup.trim();
    }

    public void setKafkaAsyncCreateOrderConsumerGroup(String kafkaAsyncCreateOrderConsumerGroup) {
        this.kafkaAsyncCreateOrderConsumerGroup = kafkaAsyncCreateOrderConsumerGroup;
    }

}
