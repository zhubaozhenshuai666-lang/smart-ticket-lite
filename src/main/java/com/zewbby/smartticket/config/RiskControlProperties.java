package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.risk-control")
public class RiskControlProperties {

    private boolean enabled = true;

    private int maxIpAttemptsPerMinute = 600;

    private int maxUserAttemptsPerMinute = 60;

    private long counterTtlSeconds = 120L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxIpAttemptsPerMinute() {
        return Math.max(1, maxIpAttemptsPerMinute);
    }

    public void setMaxIpAttemptsPerMinute(int maxIpAttemptsPerMinute) {
        this.maxIpAttemptsPerMinute = maxIpAttemptsPerMinute;
    }

    public int getMaxUserAttemptsPerMinute() {
        return Math.max(1, maxUserAttemptsPerMinute);
    }

    public void setMaxUserAttemptsPerMinute(int maxUserAttemptsPerMinute) {
        this.maxUserAttemptsPerMinute = maxUserAttemptsPerMinute;
    }

    public long getCounterTtlSeconds() {
        return Math.max(60L, counterTtlSeconds);
    }

    public void setCounterTtlSeconds(long counterTtlSeconds) {
        this.counterTtlSeconds = counterTtlSeconds;
    }
}
