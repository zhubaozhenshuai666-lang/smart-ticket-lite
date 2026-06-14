package com.zewbby.smartticket.enums;

public enum ShowStatusEnum {

    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    OFFLINE("OFFLINE");

    private final String code;

    ShowStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
