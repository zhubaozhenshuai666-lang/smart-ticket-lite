package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.show-cache")
public class ShowCacheProperties {

    private long showDetailTtlSeconds = 1800L;

    private long showDetailTtlJitterSeconds = 300L;

    private long showSessionsTtlSeconds = 600L;

    private long showSessionsTtlJitterSeconds = 120L;

    private long sessionTicketCategoriesTtlSeconds = 600L;

    private long sessionTicketCategoriesTtlJitterSeconds = 120L;

    private long nullTtlSeconds = 60L;

    private long nullTtlJitterSeconds = 30L;

    private long lockTtlSeconds = 5L;

    private long lockRetrySleepMillis = 50L;

    private int lockRetryTimes = 3;

    public long getShowDetailTtlSeconds() {
        return Math.max(1L, showDetailTtlSeconds);
    }

    public void setShowDetailTtlSeconds(long showDetailTtlSeconds) {
        this.showDetailTtlSeconds = showDetailTtlSeconds;
    }

    public long getShowDetailTtlJitterSeconds() {
        return Math.max(0L, showDetailTtlJitterSeconds);
    }

    public void setShowDetailTtlJitterSeconds(long showDetailTtlJitterSeconds) {
        this.showDetailTtlJitterSeconds = showDetailTtlJitterSeconds;
    }

    public long getShowSessionsTtlSeconds() {
        return Math.max(1L, showSessionsTtlSeconds);
    }

    public void setShowSessionsTtlSeconds(long showSessionsTtlSeconds) {
        this.showSessionsTtlSeconds = showSessionsTtlSeconds;
    }

    public long getShowSessionsTtlJitterSeconds() {
        return Math.max(0L, showSessionsTtlJitterSeconds);
    }

    public void setShowSessionsTtlJitterSeconds(long showSessionsTtlJitterSeconds) {
        this.showSessionsTtlJitterSeconds = showSessionsTtlJitterSeconds;
    }

    public long getSessionTicketCategoriesTtlSeconds() {
        return Math.max(1L, sessionTicketCategoriesTtlSeconds);
    }

    public void setSessionTicketCategoriesTtlSeconds(long sessionTicketCategoriesTtlSeconds) {
        this.sessionTicketCategoriesTtlSeconds = sessionTicketCategoriesTtlSeconds;
    }

    public long getSessionTicketCategoriesTtlJitterSeconds() {
        return Math.max(0L, sessionTicketCategoriesTtlJitterSeconds);
    }

    public void setSessionTicketCategoriesTtlJitterSeconds(long sessionTicketCategoriesTtlJitterSeconds) {
        this.sessionTicketCategoriesTtlJitterSeconds = sessionTicketCategoriesTtlJitterSeconds;
    }

    public long getNullTtlSeconds() {
        return Math.max(1L, nullTtlSeconds);
    }

    public void setNullTtlSeconds(long nullTtlSeconds) {
        this.nullTtlSeconds = nullTtlSeconds;
    }

    public long getNullTtlJitterSeconds() {
        return Math.max(0L, nullTtlJitterSeconds);
    }

    public void setNullTtlJitterSeconds(long nullTtlJitterSeconds) {
        this.nullTtlJitterSeconds = nullTtlJitterSeconds;
    }

    public long getLockTtlSeconds() {
        return Math.max(1L, lockTtlSeconds);
    }

    public void setLockTtlSeconds(long lockTtlSeconds) {
        this.lockTtlSeconds = lockTtlSeconds;
    }

    public long getLockRetrySleepMillis() {
        return Math.max(1L, lockRetrySleepMillis);
    }

    public void setLockRetrySleepMillis(long lockRetrySleepMillis) {
        this.lockRetrySleepMillis = lockRetrySleepMillis;
    }

    public int getLockRetryTimes() {
        return Math.max(1, lockRetryTimes);
    }

    public void setLockRetryTimes(int lockRetryTimes) {
        this.lockRetryTimes = lockRetryTimes;
    }
}
