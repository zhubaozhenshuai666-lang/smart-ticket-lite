package com.zewbby.smartticket.enums;

public enum RedisStockReleaseResult {

    SUCCESS(1L, "Redis预扣库存释放成功"),
    NOT_DEDUCTED(0L, "请求没有预扣记录"),
    ALREADY_COMPENSATED(-1L, "请求已经补偿过"),
    INVALID_QUANTITY(-2L, "释放数量非法"),
    INVALID_TTL(-3L, "补偿标记过期时间非法");

    private final long code;

    private final String message;

    RedisStockReleaseResult(long code, String message) {
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

    public static RedisStockReleaseResult fromCode(Long code) {
        if (code == null) {
            return NOT_DEDUCTED;
        }
        for (RedisStockReleaseResult result : values()) {
            if (result.code == code) {
                return result;
            }
        }
        return NOT_DEDUCTED;
    }
}
