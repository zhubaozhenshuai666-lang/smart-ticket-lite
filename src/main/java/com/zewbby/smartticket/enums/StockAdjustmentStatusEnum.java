package com.zewbby.smartticket.enums;

public enum StockAdjustmentStatusEnum {

    PENDING_CONFIRM("PENDING_CONFIRM"),
    CONFIRMED("CONFIRMED"),
    APPLIED("APPLIED"),
    FAILED("FAILED"),
    ROLLBACK_RECORDED("ROLLBACK_RECORDED");

    private final String code;

    StockAdjustmentStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static boolean isApplied(String status) {
        return APPLIED.code.equals(status) || ROLLBACK_RECORDED.code.equals(status);
    }
}
