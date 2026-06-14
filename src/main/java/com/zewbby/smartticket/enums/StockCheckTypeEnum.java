package com.zewbby.smartticket.enums;

public enum StockCheckTypeEnum {

    MANUAL("MANUAL", "人工触发"),
    SCHEDULED("SCHEDULED", "定时巡检");

    private final String code;

    private final String description;

    StockCheckTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static String normalize(String code) {
        if (SCHEDULED.code.equalsIgnoreCase(String.valueOf(code))) {
            return SCHEDULED.code;
        }
        return MANUAL.code;
    }
}
