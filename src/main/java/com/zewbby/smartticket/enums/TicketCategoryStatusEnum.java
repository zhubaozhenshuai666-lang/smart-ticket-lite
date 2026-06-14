package com.zewbby.smartticket.enums;

public enum TicketCategoryStatusEnum {

    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED"),
    OFFLINE("OFFLINE"),
    SOLD_OUT("SOLD_OUT");

    private final String code;

    TicketCategoryStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
