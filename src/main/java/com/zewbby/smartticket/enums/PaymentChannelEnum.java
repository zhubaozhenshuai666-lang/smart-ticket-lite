package com.zewbby.smartticket.enums;

import java.util.Locale;

public enum PaymentChannelEnum {

    MOCK("MOCK");

    private final String code;

    PaymentChannelEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static String normalize(String channel) {
        if (channel == null) {
            return null;
        }
        String normalized = channel.trim().toUpperCase(Locale.ROOT);
        for (PaymentChannelEnum value : values()) {
            if (value.code.equals(normalized)) {
                return value.code;
            }
        }
        return null;
    }
}
