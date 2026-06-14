package com.zewbby.smartticket.enums;

public enum StockCompensationTypeEnum {

    REPAIR_REDIS_TO_EXPECTED("REPAIR_REDIS_TO_EXPECTED", "按 expectedRedisAvailable 修复 Redis"),
    RELEASE_FAILED_REQUEST_DEDUCTION("RELEASE_FAILED_REQUEST_DEDUCTION", "释放失败请求的 Redis 预扣"),
    MANUAL_ADJUSTMENT("MANUAL_ADJUSTMENT", "人工调整");

    private final String code;

    private final String description;

    StockCompensationTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
