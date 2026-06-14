package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void setGetAndClearUserId() {
        UserContext.setUser(100L, "admin", "ADMIN");

        assertThat(UserContext.getUserId()).isEqualTo(100L);
        assertThat(UserContext.getUsername()).isEqualTo("admin");
        assertThat(UserContext.getRoleCode()).isEqualTo("ADMIN");
        assertThat(UserContext.requireUserId()).isEqualTo(100L);

        UserContext.clear();
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getUsername()).isNull();
        assertThat(UserContext.getRoleCode()).isNull();
    }

    @Test
    void requireUserIdRejectsMissingUser() {
        UserContext.clear();

        assertThatThrownBy(UserContext::requireUserId)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.UNAUTHORIZED);
    }
}
