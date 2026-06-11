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
        return Math.max(1, concurrentConsumers);
    }

    public void setConcurrentConsumers(int concurrentConsumers) {
        this.concurrentConsumers = concurrentConsumers;
    }

    public int getMaxConcurrentConsumers() {
        return Math.max(getConcurrentConsumers(), maxConcurrentConsumers);
    }

    public void setMaxConcurrentConsumers(int maxConcurrentConsumers) {
        this.maxConcurrentConsumers = maxConcurrentConsumers;
    }

    public int getPrefetchCount() {
        return Math.max(1, prefetchCount);
    }

    public void setPrefetchCount(int prefetchCount) {
        this.prefetchCount = prefetchCount;
    }

    public int getAsyncQueueShardCount() {
        return Math.max(1, asyncQueueShardCount);
    }

    public void setAsyncQueueShardCount(int asyncQueueShardCount) {
        this.asyncQueueShardCount = asyncQueueShardCount;
    }
}
