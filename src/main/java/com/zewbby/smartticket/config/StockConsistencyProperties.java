package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.stock-consistency")
public class StockConsistencyProperties {

    private boolean enabled = false;

    private long fixedDelaySeconds = 300L;

    private int batchSize = 100;

    private boolean autoRepairEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getFixedDelaySeconds() {
        return fixedDelaySeconds;
    }

    public void setFixedDelaySeconds(long fixedDelaySeconds) {
        this.fixedDelaySeconds = fixedDelaySeconds;
    }

    public long getFixedDelayMillis() {
        return fixedDelaySeconds * 1000L;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isAutoRepairEnabled() {
        return autoRepairEnabled;
    }

    public void setAutoRepairEnabled(boolean autoRepairEnabled) {
        this.autoRepairEnabled = autoRepairEnabled;
    }
}
