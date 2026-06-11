package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.show-relation-cache")
public class ShowRelationCacheProperties {

    private boolean enabled = true;

    private boolean failClosed = true;

    private long refreshFixedDelayMillis = 30000L;

    private long lookupCacheMaximumSize = 200000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailClosed() {
        return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }

    public long getRefreshFixedDelayMillis() {
        return Math.max(5000L, refreshFixedDelayMillis);
    }

    public void setRefreshFixedDelayMillis(long refreshFixedDelayMillis) {
        this.refreshFixedDelayMillis = refreshFixedDelayMillis;
    }

    public long getLookupCacheMaximumSize() {
        return Math.max(1000L, lookupCacheMaximumSize);
    }

    public void setLookupCacheMaximumSize(long lookupCacheMaximumSize) {
        this.lookupCacheMaximumSize = lookupCacheMaximumSize;
    }
}
