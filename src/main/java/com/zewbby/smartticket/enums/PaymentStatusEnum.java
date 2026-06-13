package com.zewbby.smartticket.enums;

public enum PaymentStatusEnum {

    INIT("INIT", "待支付"),
    PAYING("PAYING", "支付中"),
    SUCCESS("SUCCESS", "支付成功"),
    FAILED("FAILED", "支付失败"),
    CLOSED("CLOSED", "已关闭");

    private final String code;

    private final String description;

    PaymentStatusEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static boolean isUnpaid(String status) {
        return INIT.code.equals(status) || PAYING.code.equals(status);
    }

    public static boolean isSuccess(String status) {
        return SUCCESS.code.equals(status);
    }

    public static boolean isFailed(String status) {
        return FAILED.code.equals(status);
    }

    public static boolean isClosed(String status) {
        return CLOSED.code.equals(status);
    }
}
