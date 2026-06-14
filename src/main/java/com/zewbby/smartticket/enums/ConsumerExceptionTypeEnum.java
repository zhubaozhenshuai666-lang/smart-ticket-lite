package com.zewbby.smartticket.enums;

public enum ConsumerExceptionTypeEnum {

    DUPLICATE_MESSAGE("DUPLICATE_MESSAGE", "重复消息，直接确认"),
    BUSINESS_REJECT("BUSINESS_REJECT", "业务不可恢复失败"),
    TRANSIENT_SYSTEM_ERROR("TRANSIENT_SYSTEM_ERROR", "可重试系统异常"),
    DATA_INCONSISTENCY("DATA_INCONSISTENCY", "数据不一致异常"),
    UNKNOWN_ERROR("UNKNOWN_ERROR", "未分类异常");

    private final String code;

    private final String description;

    ConsumerExceptionTypeEnum(String code, String description) {
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
