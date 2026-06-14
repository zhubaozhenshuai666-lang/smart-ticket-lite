package com.zewbby.smartticket.enums;

public enum AdminOperationResultEnum {

    SUCCESS("SUCCESS"),
    FAILED("FAILED");

    private final String code;

    AdminOperationResultEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
