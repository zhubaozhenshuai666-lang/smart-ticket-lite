package com.zewbby.smartticket.enums;

import java.util.Arrays;

public enum UserRoleEnum {

    USER("USER"),
    ADMIN("ADMIN"),
    OPERATOR("OPERATOR");

    private final String code;

    UserRoleEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static UserRoleEnum normalize(String roleCode) {
        return Arrays.stream(values())
                .filter(role -> role.code.equalsIgnoreCase(String.valueOf(roleCode)))
                .findFirst()
                .orElse(USER);
    }

    public static boolean isBackstageRole(String roleCode) {
        UserRoleEnum role = normalize(roleCode);
        return role == ADMIN || role == OPERATOR;
    }

    public static boolean isAdmin(String roleCode) {
        return ADMIN == normalize(roleCode);
    }

    public static boolean isOperator(String roleCode) {
        return OPERATOR == normalize(roleCode);
    }
}
