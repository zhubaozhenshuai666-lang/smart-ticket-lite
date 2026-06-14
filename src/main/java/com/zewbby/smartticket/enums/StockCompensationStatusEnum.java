package com.zewbby.smartticket.enums;

public enum StockCompensationStatusEnum {

    SUCCESS("SUCCESS", "补偿成功"),
    FAILED("FAILED", "补偿失败"),
    CONCURRENT_MODIFIED("CONCURRENT_MODIFIED", "并发修改，放弃本次补偿");

    private final String code;

    private final String description;

    StockCompensationStatusEnum(String code, String description) {
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
