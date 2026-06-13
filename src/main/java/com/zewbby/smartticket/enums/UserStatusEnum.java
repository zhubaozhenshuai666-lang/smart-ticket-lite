package com.zewbby.smartticket.enums;

public enum UserStatusEnum {

    NORMAL,
    LOCKED,
    DISABLED,
    DELETED;

    public static boolean isNormal(String status) {
        return NORMAL.name().equals(status);
    }
}
