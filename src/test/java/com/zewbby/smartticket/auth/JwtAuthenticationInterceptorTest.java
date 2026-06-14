package com.zewbby.smartticket.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.entity.UserAccount;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationInterceptorTest {

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void bearerTokenSetsAndClearsUserContext() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(120), new ObjectMapper());
        JwtAuthenticationInterceptor interceptor = new JwtAuthenticationInterceptor(provider, tokenBlacklistService);
        String token = provider.generateToken(user());
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(UserContext.requireUserId()).isEqualTo(1L);
        assertThat(UserContext.getUsername()).isEqualTo("tester");
        assertThat(UserContext.getRoleCode()).isEqualTo("ADMIN");

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(UserContext.getUserId()).isNull();
        assertThat(UserContext.getRoleCode()).isNull();
    }

    @Test
    void missingBearerTokenIsRejected() {
        JwtAuthenticationInterceptor interceptor = new JwtAuthenticationInterceptor(
                new JwtTokenProvider(jwtProperties(120), new ObjectMapper()),
                tokenBlacklistService
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.UNAUTHORIZED);
    }

    @Test
    void nonBearerTokenIsRejectedAsInvalidToken() {
        JwtAuthenticationInterceptor interceptor = new JwtAuthenticationInterceptor(
                new JwtTokenProvider(jwtProperties(120), new ObjectMapper()),
                tokenBlacklistService
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Token abc");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.TOKEN_INVALID);
    }

    @Test
    void blacklistedTokenIsRejectedForProtectedEndpoint() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(120), new ObjectMapper());
        JwtAuthenticationInterceptor interceptor = new JwtAuthenticationInterceptor(provider, tokenBlacklistService);
        String token = provider.generateToken(user());
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader("Authorization", "Bearer " + token);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.TOKEN_LOGGED_OUT);
    }

    @Test
    void blacklistedTokenCanCallLogoutAgainForIdempotency() {
        JwtTokenProvider provider = new JwtTokenProvider(jwtProperties(120), new ObjectMapper());
        JwtAuthenticationInterceptor interceptor = new JwtAuthenticationInterceptor(provider, tokenBlacklistService);
        String token = provider.generateToken(user());
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
        request.addHeader("Authorization", "Bearer " + token);

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(UserContext.requireUserId()).isEqualTo(1L);
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
