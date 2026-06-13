package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private String algorithm = "token-bucket";

    private int orderUserCapacity = 5;

    private double orderUserRefillRatePerSecond = 1D;

    private int orderIpCapacity = 30;

    private double orderIpRefillRatePerSecond = 5D;

    private int orderApiCapacity = 500;

    private double orderApiRefillRatePerSecond = 100D;

    private int orderTicketCapacity = 100;

    private double orderTicketRefillRatePerSecond = 20D;

    private long keyTtlSeconds = 120L;

    private long soldoutTtlSeconds = 600L;

    private boolean backpressureEnabled = false;

    private long localMessageBacklogRejectThreshold = 10000L;

    private long backpressureSampleIntervalMillis = 1000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public int getOrderUserCapacity() {
        return orderUserCapacity;
    }

    public void setOrderUserCapacity(int orderUserCapacity) {
        this.orderUserCapacity = orderUserCapacity;
    }

    public double getOrderUserRefillRatePerSecond() {
        return orderUserRefillRatePerSecond;
    }

    public void setOrderUserRefillRatePerSecond(double orderUserRefillRatePerSecond) {
        this.orderUserRefillRatePerSecond = orderUserRefillRatePerSecond;
    }

    public int getOrderIpCapacity() {
        return orderIpCapacity;
    }

    public void setOrderIpCapacity(int orderIpCapacity) {
        this.orderIpCapacity = orderIpCapacity;
    }

    public double getOrderIpRefillRatePerSecond() {
        return orderIpRefillRatePerSecond;
    }

    public void setOrderIpRefillRatePerSecond(double orderIpRefillRatePerSecond) {
        this.orderIpRefillRatePerSecond = orderIpRefillRatePerSecond;
    }

    public int getOrderApiCapacity() {
        return orderApiCapacity;
    }

    public void setOrderApiCapacity(int orderApiCapacity) {
        this.orderApiCapacity = orderApiCapacity;
    }

    public double getOrderApiRefillRatePerSecond() {
        return orderApiRefillRatePerSecond;
    }

    public void setOrderApiRefillRatePerSecond(double orderApiRefillRatePerSecond) {
        this.orderApiRefillRatePerSecond = orderApiRefillRatePerSecond;
    }

    public int getOrderTicketCapacity() {
        return orderTicketCapacity;
    }

    public void setOrderTicketCapacity(int orderTicketCapacity) {
        this.orderTicketCapacity = orderTicketCapacity;
    }

    public double getOrderTicketRefillRatePerSecond() {
        return orderTicketRefillRatePerSecond;
    }

    public void setOrderTicketRefillRatePerSecond(double orderTicketRefillRatePerSecond) {
        this.orderTicketRefillRatePerSecond = orderTicketRefillRatePerSecond;
    }

    public long getKeyTtlSeconds() {
        return keyTtlSeconds;
    }

    public void setKeyTtlSeconds(long keyTtlSeconds) {
        this.keyTtlSeconds = keyTtlSeconds;
    }

    public long getSoldoutTtlSeconds() {
        return soldoutTtlSeconds;
    }

    public void setSoldoutTtlSeconds(long soldoutTtlSeconds) {
        this.soldoutTtlSeconds = soldoutTtlSeconds;
    }

    public boolean isBackpressureEnabled() {
        return backpressureEnabled;
    }

    public void setBackpressureEnabled(boolean backpressureEnabled) {
        this.backpressureEnabled = backpressureEnabled;
    }

    public long getLocalMessageBacklogRejectThreshold() {
        return Math.max(1L, localMessageBacklogRejectThreshold);
    }

    public void setLocalMessageBacklogRejectThreshold(long localMessageBacklogRejectThreshold) {
        this.localMessageBacklogRejectThreshold = localMessageBacklogRejectThreshold;
    }

    public long getBackpressureSampleIntervalMillis() {
        return Math.max(100L, backpressureSampleIntervalMillis);
    }

    public void setBackpressureSampleIntervalMillis(long backpressureSampleIntervalMillis) {
        this.backpressureSampleIntervalMillis = backpressureSampleIntervalMillis;
    }
}
