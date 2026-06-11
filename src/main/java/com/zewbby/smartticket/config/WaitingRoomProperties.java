package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.waiting-room")
public class WaitingRoomProperties {

    private boolean enabled = false;

    private long admissionTokenExpireSeconds = 120L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getAdmissionTokenExpireSeconds() {
        return Math.max(10L, admissionTokenExpireSeconds);
    }

    public void setAdmissionTokenExpireSeconds(long admissionTokenExpireSeconds) {
        this.admissionTokenExpireSeconds = admissionTokenExpireSeconds;
    }
}
