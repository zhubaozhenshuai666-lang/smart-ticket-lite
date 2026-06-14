package com.zewbby.smartticket.enums;

public enum LocalMessageBusinessTypeEnum {

    ASYNC_CREATE_ORDER("ASYNC_CREATE_ORDER", "异步创建订单"),

    ORDER_TIMEOUT_CLOSE("ORDER_TIMEOUT_CLOSE", "订单超时关闭");

    private final String code;

    private final String description;

    LocalMessageBusinessTypeEnum(String code, String description) {
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
