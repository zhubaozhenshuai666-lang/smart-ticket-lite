package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.mq-consumer")
public class MqConsumerProperties {

    private int maxRetryCount = 3;

    private long retryIntervalSeconds = 10L;

    private long processingTimeoutSeconds = 120L;

    private int concurrentConsumers = 8;

    private int maxConcurrentConsumers = 64;

    private int prefetchCount = 10;

    private int asyncQueueShardCount = 16;

    private int maxConcurrentConsumerCap = 128;

    private int maxPrefetchCount = 50;

    private int maxUnackedMessages = 1000;

    private int maxAsyncQueueShardCount = 64;

    private int rocketMqConsumeThreadNumber = 24;

    private int rocketMqConsumeThreadMax = 96;

    private int rocketMqPullBatchSize = 64;

    private int rocketMqConsumeMessageBatchMaxSize = 16;

    private int rocketMqPullThresholdForQueue = 1000;

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public long getRetryIntervalSeconds() {
        return retryIntervalSeconds;
    }

    public void setRetryIntervalSeconds(long retryIntervalSeconds) {
        this.retryIntervalSeconds = retryIntervalSeconds;
    }

    public long getProcessingTimeoutSeconds() {
        return processingTimeoutSeconds;
    }

    public void setProcessingTimeoutSeconds(long processingTimeoutSeconds) {
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    public int getConcurrentConsumers() {
        return Math.min(Math.max(1, concurrentConsumers), getMaxConcurrentConsumerCap());
    }

    public void setConcurrentConsumers(int concurrentConsumers) {
        this.concurrentConsumers = concurrentConsumers;
    }

    public int getMaxConcurrentConsumers() {
        return Math.min(Math.max(getConcurrentConsumers(), maxConcurrentConsumers), getMaxConcurrentConsumerCap());
    }

    public void setMaxConcurrentConsumers(int maxConcurrentConsumers) {
        this.maxConcurrentConsumers = maxConcurrentConsumers;
    }

    public int getPrefetchCount() {
        int boundedPrefetch = Math.min(Math.max(1, prefetchCount), getMaxPrefetchCount());
        int perConsumerBudget = Math.max(1, getMaxUnackedMessages() / getMaxConcurrentConsumers());
        return Math.min(boundedPrefetch, perConsumerBudget);
    }

    public void setPrefetchCount(int prefetchCount) {
        this.prefetchCount = prefetchCount;
    }

    public int getAsyncQueueShardCount() {
        return Math.min(Math.max(1, asyncQueueShardCount), getMaxAsyncQueueShardCount());
    }

    public void setAsyncQueueShardCount(int asyncQueueShardCount) {
        this.asyncQueueShardCount = asyncQueueShardCount;
    }

    public int getMaxConcurrentConsumerCap() {
        return Math.max(1, maxConcurrentConsumerCap);
    }

    public void setMaxConcurrentConsumerCap(int maxConcurrentConsumerCap) {
        this.maxConcurrentConsumerCap = maxConcurrentConsumerCap;
    }

    public int getMaxPrefetchCount() {
        return Math.max(1, maxPrefetchCount);
    }

    public void setMaxPrefetchCount(int maxPrefetchCount) {
        this.maxPrefetchCount = maxPrefetchCount;
    }

    public int getMaxUnackedMessages() {
        return Math.max(1, maxUnackedMessages);
    }

    public void setMaxUnackedMessages(int maxUnackedMessages) {
        this.maxUnackedMessages = maxUnackedMessages;
    }

    public int getMaxAsyncQueueShardCount() {
        return Math.max(1, maxAsyncQueueShardCount);
    }

    public void setMaxAsyncQueueShardCount(int maxAsyncQueueShardCount) {
        this.maxAsyncQueueShardCount = maxAsyncQueueShardCount;
    }

    public int getRocketMqConsumeThreadNumber() {
        return Math.min(Math.max(1, rocketMqConsumeThreadNumber), getMaxConcurrentConsumerCap());
    }

    public void setRocketMqConsumeThreadNumber(int rocketMqConsumeThreadNumber) {
        this.rocketMqConsumeThreadNumber = rocketMqConsumeThreadNumber;
    }

    public int getRocketMqConsumeThreadMax() {
        return Math.min(Math.max(getRocketMqConsumeThreadNumber(), rocketMqConsumeThreadMax), getMaxConcurrentConsumerCap());
    }

    public void setRocketMqConsumeThreadMax(int rocketMqConsumeThreadMax) {
        this.rocketMqConsumeThreadMax = rocketMqConsumeThreadMax;
    }

    public int getRocketMqPullBatchSize() {
        return Math.min(Math.max(1, rocketMqPullBatchSize), 256);
    }

    public void setRocketMqPullBatchSize(int rocketMqPullBatchSize) {
        this.rocketMqPullBatchSize = rocketMqPullBatchSize;
    }

    public int getRocketMqConsumeMessageBatchMaxSize() {
        return Math.min(Math.max(1, rocketMqConsumeMessageBatchMaxSize), 64);
    }

    public void setRocketMqConsumeMessageBatchMaxSize(int rocketMqConsumeMessageBatchMaxSize) {
        this.rocketMqConsumeMessageBatchMaxSize = rocketMqConsumeMessageBatchMaxSize;
    }

    public int getRocketMqPullThresholdForQueue() {
        return Math.min(Math.max(1, rocketMqPullThresholdForQueue), 10_000);
    }

    public void setRocketMqPullThresholdForQueue(int rocketMqPullThresholdForQueue) {
        this.rocketMqPullThresholdForQueue = rocketMqPullThresholdForQueue;
    }
}
