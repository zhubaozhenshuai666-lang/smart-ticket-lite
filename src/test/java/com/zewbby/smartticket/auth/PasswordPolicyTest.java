package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @Test
    void acceptsPasswordWithLettersDigitsAndCommonEnglishSpecialCharacters() {
        assertThatCode(() -> PasswordPolicy.validate("tester", "13800000000", "Test123456!@#"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordWithoutDigit() {
        assertWeakPassword("Password!");
    }

    @Test
    void rejectsPasswordWithoutLetter() {
        assertWeakPassword("12345678!");
    }

    @Test
    void rejectsPasswordContainingChineseCharacters() {
        assertWeakPassword("Test123中文");
    }

    @Test
    void rejectsPasswordContainingBlankSpace() {
        assertWeakPassword("Test 123456");
    }

    @Test
    void rejectsPasswordContainingUnsupportedSymbol() {
        assertWeakPassword("Test123456￥");
    }

    private void assertWeakPassword(String password) {
        assertThatThrownBy(() -> PasswordPolicy.validate("tester", "13800000000", password))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PASSWORD_WEAK);
    }
}
