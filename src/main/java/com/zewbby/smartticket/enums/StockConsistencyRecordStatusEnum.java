package com.zewbby.smartticket.enums;

public enum StockConsistencyRecordStatusEnum {

    PENDING("PENDING", "待处理"),
    REPAIRED("REPAIRED", "已修复"),
    IGNORED("IGNORED", "已忽略"),
    FAILED("FAILED", "修复失败");

    private final String code;

    private final String description;

    StockConsistencyRecordStatusEnum(String code, String description) {
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
