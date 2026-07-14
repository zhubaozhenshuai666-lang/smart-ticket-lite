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

    private boolean asyncOrderBatchEnabled = false;

    private int asyncOrderBatchWorkerCount = 8;

    private int maxAsyncOrderBatchWorkerCount = 64;

    private int asyncOrderBatchSize = 32;

    private long asyncOrderBatchMaxWaitMillis = 20L;

    private int asyncOrderBatchQueueCapacity = 4096;

    private long asyncOrderBatchOfferTimeoutMillis = 50L;

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

    public boolean isAsyncOrderBatchEnabled() {
        return asyncOrderBatchEnabled;
    }

    public void setAsyncOrderBatchEnabled(boolean asyncOrderBatchEnabled) {
        this.asyncOrderBatchEnabled = asyncOrderBatchEnabled;
    }

    public int getAsyncOrderBatchWorkerCount() {
        return Math.min(Math.max(1, asyncOrderBatchWorkerCount), getMaxAsyncOrderBatchWorkerCount());
    }

    public void setAsyncOrderBatchWorkerCount(int asyncOrderBatchWorkerCount) {
        this.asyncOrderBatchWorkerCount = asyncOrderBatchWorkerCount;
    }

    public int getMaxAsyncOrderBatchWorkerCount() {
        return Math.max(1, maxAsyncOrderBatchWorkerCount);
    }

    public void setMaxAsyncOrderBatchWorkerCount(int maxAsyncOrderBatchWorkerCount) {
        this.maxAsyncOrderBatchWorkerCount = maxAsyncOrderBatchWorkerCount;
    }

    public int getAsyncOrderBatchSize() {
        return Math.min(Math.max(1, asyncOrderBatchSize), 256);
    }

    public void setAsyncOrderBatchSize(int asyncOrderBatchSize) {
        this.asyncOrderBatchSize = asyncOrderBatchSize;
    }

    public long getAsyncOrderBatchMaxWaitMillis() {
        return Math.min(Math.max(1L, asyncOrderBatchMaxWaitMillis), 1000L);
    }

    public void setAsyncOrderBatchMaxWaitMillis(long asyncOrderBatchMaxWaitMillis) {
        this.asyncOrderBatchMaxWaitMillis = asyncOrderBatchMaxWaitMillis;
    }

    public int getAsyncOrderBatchQueueCapacity() {
        return Math.min(Math.max(1, asyncOrderBatchQueueCapacity), 100_000);
    }

    public void setAsyncOrderBatchQueueCapacity(int asyncOrderBatchQueueCapacity) {
        this.asyncOrderBatchQueueCapacity = asyncOrderBatchQueueCapacity;
    }

    public long getAsyncOrderBatchOfferTimeoutMillis() {
        return Math.min(Math.max(1L, asyncOrderBatchOfferTimeoutMillis), 1000L);
    }

    public void setAsyncOrderBatchOfferTimeoutMillis(long asyncOrderBatchOfferTimeoutMillis) {
        this.asyncOrderBatchOfferTimeoutMillis = asyncOrderBatchOfferTimeoutMillis;
    }
}
