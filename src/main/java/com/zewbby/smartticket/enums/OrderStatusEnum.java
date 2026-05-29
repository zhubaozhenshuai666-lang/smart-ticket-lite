package com.zewbby.smartticket.enums;

public enum OrderStatusEnum {

    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
    PAID("PAID", "已支付"),
    CANCELLED("CANCELLED", "已取消"),
    CLOSED("CLOSED", "已关闭");

    private final String code;

    private final String description;

    OrderStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isPendingPayment(String status) {
        return PENDING_PAYMENT.code.equals(status);
    }

    public static boolean isPaid(String status) {
        return PAID.code.equals(status);
    }

    public static boolean isCancelled(String status) {
        return CANCELLED.code.equals(status);
    }

    public static boolean isClosed(String status) {
        return CLOSED.code.equals(status);
    }
}
