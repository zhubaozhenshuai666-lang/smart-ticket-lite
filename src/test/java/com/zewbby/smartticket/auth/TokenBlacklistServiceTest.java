package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenBlacklistServiceTest {

    private StringRedisTemplate stringRedisTemplate;

    private ValueOperations<String, String> valueOperations;

    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        tokenBlacklistService = new TokenBlacklistService(stringRedisTemplate);
    }

    @Test
    void blacklistStoresJtiWithRemainingTtl() {
        JwtUserClaims claims = new JwtUserClaims(
                1L,
                "tester",
                "13800000000",
                "USER",
                "jti123",
                Instant.now(),
                Instant.now().plusSeconds(120)
        );

        tokenBlacklistService.blacklist(claims);

        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(RedisKeyConstant.authTokenBlacklistKey("jti123")),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.any(Duration.class)
        );
    }

    @Test
    void isBlacklistedReturnsTrueWhenRedisKeyExists() {
        when(stringRedisTemplate.hasKey(RedisKeyConstant.authTokenBlacklistKey("jti123"))).thenReturn(true);

        assertThat(tokenBlacklistService.isBlacklisted("jti123")).isTrue();
    }
}
