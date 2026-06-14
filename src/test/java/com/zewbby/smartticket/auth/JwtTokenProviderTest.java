package com.zewbby.smartticket.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.entity.UserAccount;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    @Test
    void parseTokenReturnsUserClaimsWhenTokenIsValid() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(120), new ObjectMapper());

        String token = provider.generateToken(user());
        JwtUserClaims claims = provider.parseToken(token);

        assertThat(claims.getUserId()).isEqualTo(1L);
        assertThat(claims.getUsername()).isEqualTo("tester");
        assertThat(claims.getPhone()).isEqualTo("13800000000");
        assertThat(claims.getRoleCode()).isEqualTo("ADMIN");
        assertThat(claims.getJti()).isNotBlank();
        assertThat(claims.getExpireAt()).isAfter(claims.getIssuedAt());
    }

    @Test
    void parseTokenRejectsExpiredToken() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(-1), new ObjectMapper());
        String token = provider.generateToken(user());

        assertThatThrownBy(() -> provider.parseToken(token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.TOKEN_EXPIRED);
    }

    @Test
    void parseTokenRejectsInvalidToken() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(120), new ObjectMapper());

        assertThatThrownBy(() -> provider.parseToken("invalid.token.value"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.TOKEN_INVALID);
    }

    private UserAccount user() {
        return new UserAccount(
                1L,
                "tester",
                "13800000000",
                "encoded",
                "NORMAL",
                "ADMIN",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private JwtProperties jwtProperties(long expireMinutes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("smart-ticket-test-secret-at-least-32-bytes");
        properties.setExpireMinutes(expireMinutes);
        return properties;
    }
}
