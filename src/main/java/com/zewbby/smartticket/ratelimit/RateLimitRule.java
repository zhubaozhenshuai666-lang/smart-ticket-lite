package com.zewbby.smartticket.ratelimit;

public class RateLimitRule {

    private final int limit;

    private final long windowSeconds;

    public RateLimitRule(int limit, long windowSeconds) {
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }
}
