package com.zewbby.smartticket.enums;

public enum RedisStockRepairResult {

    SUCCESS(1L, "修复成功"),
    STOCK_NOT_FOUND(-1L, "Redis库存key不存在"),
    CONCURRENT_MODIFIED(-2L, "Redis库存已被并发修改"),
    STOCK_VALUE_INVALID(-3L, "Redis库存值不是整数");

    private final long code;

    private final String message;

    RedisStockRepairResult(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public static RedisStockRepairResult fromCode(Long code) {
        if (code == null) {
            return STOCK_VALUE_INVALID;
        }
        for (RedisStockRepairResult result : values()) {
            if (result.code == code) {
                return result;
            }
        }
        return STOCK_VALUE_INVALID;
    }
}
