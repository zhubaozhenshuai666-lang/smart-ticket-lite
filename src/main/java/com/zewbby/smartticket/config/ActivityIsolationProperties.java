package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.activity-isolation")
public class ActivityIsolationProperties {

    private boolean enabled = true;

    private long maxInFlightPerActivityTicketCategory = 100000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxInFlightPerActivityTicketCategory() {
        return Math.max(1L, maxInFlightPerActivityTicketCategory);
    }

    public void setMaxInFlightPerActivityTicketCategory(long maxInFlightPerActivityTicketCategory) {
        this.maxInFlightPerActivityTicketCategory = maxInFlightPerActivityTicketCategory;
    }
}
