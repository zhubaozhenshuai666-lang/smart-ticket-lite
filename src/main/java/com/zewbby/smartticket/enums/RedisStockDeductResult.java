package com.zewbby.smartticket.enums;

public enum RedisStockDeductResult {

    PROBE_MISS(2L, "抢票人数过多，请稍后重试"),
    SUCCESS(1L, "Redis库存预扣成功"),
    STOCK_NOT_ENOUGH(0L, "库存不足"),
    STOCK_NOT_FOUND(-1L, "Redis库存未预热"),
    DUPLICATE(-2L, "请求已预扣库存"),
    INVALID_QUANTITY(-3L, "扣减数量非法"),
    BUCKET_NOT_FOUND(-4L, "Redis库存bucket不存在"),
    STOCK_VALUE_INVALID(-5L, "Redis库存值不是整数");

    private final long code;

    private final String message;

    RedisStockDeductResult(long code, String message) {
        this.code = code;
        this.message = message;
    }

    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public static RedisStockDeductResult fromCode(Long code) {
        if (code == null) {
            return STOCK_VALUE_INVALID;
        }
        for (RedisStockDeductResult result : values()) {
            if (result.code == code) {
                return result;
            }
        }
        return STOCK_VALUE_INVALID;
    }
}
