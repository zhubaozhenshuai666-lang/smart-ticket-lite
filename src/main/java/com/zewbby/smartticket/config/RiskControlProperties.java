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

    private boolean gatewayDecisionEnabled = true;

    private String gatewayDecisionHeaderName = "X-Smart-Ticket-Risk-Decision";

    private String gatewayPassValue = "pass";

    private String gatewayRejectValue = "reject";

    private boolean gatewayDecisionRequired = false;

    private boolean skipLocalCounterWhenGatewayPass = false;

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

    public boolean isGatewayDecisionEnabled() {
        return gatewayDecisionEnabled;
    }

    public void setGatewayDecisionEnabled(boolean gatewayDecisionEnabled) {
        this.gatewayDecisionEnabled = gatewayDecisionEnabled;
    }

    public String getGatewayDecisionHeaderName() {
        return gatewayDecisionHeaderName == null || gatewayDecisionHeaderName.isBlank()
                ? "X-Smart-Ticket-Risk-Decision"
                : gatewayDecisionHeaderName.trim();
    }

    public void setGatewayDecisionHeaderName(String gatewayDecisionHeaderName) {
        this.gatewayDecisionHeaderName = gatewayDecisionHeaderName;
    }

    public String getGatewayPassValue() {
        return gatewayPassValue == null || gatewayPassValue.isBlank() ? "pass" : gatewayPassValue.trim();
    }

    public void setGatewayPassValue(String gatewayPassValue) {
        this.gatewayPassValue = gatewayPassValue;
    }

    public String getGatewayRejectValue() {
        return gatewayRejectValue == null || gatewayRejectValue.isBlank() ? "reject" : gatewayRejectValue.trim();
    }

    public void setGatewayRejectValue(String gatewayRejectValue) {
        this.gatewayRejectValue = gatewayRejectValue;
    }

    public boolean isGatewayDecisionRequired() {
        return gatewayDecisionRequired;
    }

    public void setGatewayDecisionRequired(boolean gatewayDecisionRequired) {
        this.gatewayDecisionRequired = gatewayDecisionRequired;
    }

    public boolean isSkipLocalCounterWhenGatewayPass() {
        return skipLocalCounterWhenGatewayPass;
    }

    public void setSkipLocalCounterWhenGatewayPass(boolean skipLocalCounterWhenGatewayPass) {
        this.skipLocalCounterWhenGatewayPass = skipLocalCounterWhenGatewayPass;
    }
}
