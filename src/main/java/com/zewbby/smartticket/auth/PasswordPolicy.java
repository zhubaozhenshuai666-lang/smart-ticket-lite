package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;

public final class PasswordPolicy {

    private static final int MIN_LENGTH = 8;

    private static final int MAX_LENGTH = 32;

    private static final String ALLOWED_SPECIAL_CHARS = "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~";

    private PasswordPolicy() {
    }

    public static void validate(String username, String phone, String password) {
        if (password == null || password.isBlank()) {
            throw new BusinessException(ErrorMessageConstant.PASSWORD_WEAK);
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw new BusinessException(ErrorMessageConstant.PASSWORD_WEAK);
        }
        if (password.equals(username) || password.equals(phone)) {
            throw new BusinessException(ErrorMessageConstant.PASSWORD_WEAK);
        }

        boolean containsOnlyAllowedCharacters = password.chars()
                .allMatch(PasswordPolicy::isAllowedPasswordChar);
        if (!containsOnlyAllowedCharacters) {
            throw new BusinessException(ErrorMessageConstant.PASSWORD_WEAK);
        }

        boolean hasLetter = password.chars().anyMatch(PasswordPolicy::isAsciiLetter);
        boolean hasDigit = password.chars().anyMatch(PasswordPolicy::isAsciiDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorMessageConstant.PASSWORD_WEAK);
        }
    }

    private static boolean isAllowedPasswordChar(int codePoint) {
        return isAsciiLetter(codePoint)
                || isAsciiDigit(codePoint)
                || ALLOWED_SPECIAL_CHARS.indexOf(codePoint) >= 0;
    }

    private static boolean isAsciiLetter(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z');
    }

    private static boolean isAsciiDigit(int codePoint) {
        return codePoint >= '0' && codePoint <= '9';
    }
}
