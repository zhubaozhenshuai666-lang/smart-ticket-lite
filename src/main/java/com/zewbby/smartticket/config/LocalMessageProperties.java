package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.local-message")
public class LocalMessageProperties {

    private boolean senderEnabled = true;

    private int batchSize = 100;

    private long confirmTimeoutSeconds = 60L;

    private int defaultMaxRetryCount = 5;

    private boolean markSentEnabled = false;

    public boolean isSenderEnabled() {
        return senderEnabled;
    }

    public void setSenderEnabled(boolean senderEnabled) {
        this.senderEnabled = senderEnabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getConfirmTimeoutSeconds() {
        return confirmTimeoutSeconds;
    }

    public void setConfirmTimeoutSeconds(long confirmTimeoutSeconds) {
        this.confirmTimeoutSeconds = confirmTimeoutSeconds;
    }

    public int getDefaultMaxRetryCount() {
        return defaultMaxRetryCount;
    }

    public void setDefaultMaxRetryCount(int defaultMaxRetryCount) {
        this.defaultMaxRetryCount = defaultMaxRetryCount;
    }

    public boolean isMarkSentEnabled() {
        return markSentEnabled;
    }

    public void setMarkSentEnabled(boolean markSentEnabled) {
        this.markSentEnabled = markSentEnabled;
    }
}
