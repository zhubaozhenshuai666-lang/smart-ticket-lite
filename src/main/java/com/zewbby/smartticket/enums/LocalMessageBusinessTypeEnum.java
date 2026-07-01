package com.zewbby.smartticket.enums;

public enum LocalMessageBusinessTypeEnum {

    ASYNC_CREATE_ORDER("ASYNC_CREATE_ORDER", "异步创建订单"),

    ORDER_TIMEOUT_CLOSE("ORDER_TIMEOUT_CLOSE", "订单超时关闭"),

    ORDER_CREATED_EVENT("ORDER_CREATED_EVENT", "订单创建事件"),

    PAYMENT_PAID_EVENT("PAYMENT_PAID_EVENT", "支付成功事件"),

    STOCK_CHANGED_EVENT("STOCK_CHANGED_EVENT", "库存变化事件");

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
