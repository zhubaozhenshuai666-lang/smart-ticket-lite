package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginFailureServiceTest {

    private StringRedisTemplate stringRedisTemplate;

    private ValueOperations<String, String> valueOperations;

    private LoginFailureService loginFailureService;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        AuthProperties authProperties = new AuthProperties();
        authProperties.setLoginFailThreshold(5);
        authProperties.setLoginLockMinutes(10);
        loginFailureService = new LoginFailureService(stringRedisTemplate, authProperties);
    }

    @Test
    void checkLoginAllowedRejectsLockedLoginName() {
        when(stringRedisTemplate.hasKey(RedisKeyConstant.authLoginLockKey("13800000000"))).thenReturn(true);

        assertThatThrownBy(() -> loginFailureService.checkLoginAllowed("13800000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.LOGIN_LOCKED);
    }

    @Test
    void recordFailureIncrementsFailCount() {
        String failKey = RedisKeyConstant.authLoginFailKey("13800000000");
        when(valueOperations.increment(failKey)).thenReturn(1L);

        loginFailureService.recordFailure("13800000000");

        verify(valueOperations).increment(failKey);
        verify(stringRedisTemplate).expire(failKey, Duration.ofMinutes(10));
    }

    @Test
    void recordFailureLocksLoginNameWhenThresholdReached() {
        String failKey = RedisKeyConstant.authLoginFailKey("13800000000");
        String lockKey = RedisKeyConstant.authLoginLockKey("13800000000");
        when(valueOperations.increment(failKey)).thenReturn(5L);

        loginFailureService.recordFailure("13800000000");

        verify(valueOperations).set(lockKey, "1", Duration.ofMinutes(10));
    }

    @Test
    void clearFailureDeletesFailAndLockKeys() {
        loginFailureService.clearFailure("13800000000");

        verify(stringRedisTemplate).delete(RedisKeyConstant.authLoginFailKey("13800000000"));
        verify(stringRedisTemplate).delete(RedisKeyConstant.authLoginLockKey("13800000000"));
    }
}
