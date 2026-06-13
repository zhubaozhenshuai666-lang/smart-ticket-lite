package com.zewbby.smartticket.enums;

public enum CompensationStatusEnum {

    NONE("NONE", "未开始补偿"),
    COMPENSATING("COMPENSATING", "补偿处理中"),
    COMPENSATED("COMPENSATED", "已补偿"),
    COMPENSATE_FAILED("COMPENSATE_FAILED", "补偿失败，等待巡检或人工处理");

    private final String code;

    private final String description;

    CompensationStatusEnum(String code, String description) {
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
