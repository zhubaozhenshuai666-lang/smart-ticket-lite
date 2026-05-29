package com.zewbby.smartticket.enums;

public enum OrderRequestStatusEnum {

    PROCESSING("PROCESSING", "处理中"),
    SUCCESS("SUCCESS", "处理成功"),
    FAILED("FAILED", "处理失败");

    private final String code;

    private final String description;

    OrderRequestStatusEnum(String code, String description) {
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
