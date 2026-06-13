package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.order-timeout")
public class OrderTimeoutProperties {

    private boolean delayMessageEnabled = true;

    private int scanBatchSize = 1000;

    private long scanFixedDelayMillis = 1000L;

    public boolean isDelayMessageEnabled() {
        return delayMessageEnabled;
    }

    public void setDelayMessageEnabled(boolean delayMessageEnabled) {
        this.delayMessageEnabled = delayMessageEnabled;
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
}
