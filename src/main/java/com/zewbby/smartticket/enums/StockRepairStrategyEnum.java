package com.zewbby.smartticket.enums;

public enum StockRepairStrategyEnum {

    REPAIR_REDIS_TO_EXPECTED("REPAIR_REDIS_TO_EXPECTED", "Lua CAS + Delta 修复 Redis 到 expected 值");

    private final String code;

    private final String description;

    StockRepairStrategyEnum(String code, String description) {
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
