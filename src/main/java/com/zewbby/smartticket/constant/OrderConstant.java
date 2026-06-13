package com.zewbby.smartticket.constant;

public final class OrderConstant {

    public static final int ORDER_TIMEOUT_MINUTES = 10;

    public static final int ORDER_TIMEOUT_TTL_MILLIS = ORDER_TIMEOUT_MINUTES * 60 * 1000;

    private OrderConstant() {
    }
}
