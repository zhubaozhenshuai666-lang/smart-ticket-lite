package com.zewbby.smartticket.enums;

public enum DeadLetterStatusEnum {

    PENDING("PENDING", "待人工处理"),
    RETRIED("RETRIED", "已发起人工重试"),
    RESOLVED("RESOLVED", "已确认解决"),
    IGNORED("IGNORED", "已人工忽略"),
    FAILED("FAILED", "人工重试失败");

    private final String code;

    private final String description;

    DeadLetterStatusEnum(String code, String description) {
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
