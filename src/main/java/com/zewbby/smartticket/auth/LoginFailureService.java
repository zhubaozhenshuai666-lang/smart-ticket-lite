package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class LoginFailureService {

    private final StringRedisTemplate stringRedisTemplate;

    private final AuthProperties authProperties;

    public LoginFailureService(StringRedisTemplate stringRedisTemplate, AuthProperties authProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.authProperties = authProperties;
    }

    /**
     * 检查是否多次登陆失败被锁定
     * @param loginName
     */
    public void checkLoginAllowed(String loginName) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.authLoginLockKey(loginName)))) {
            throw new BusinessException(ErrorMessageConstant.LOGIN_LOCKED);
        }
    }

    public void recordFailure(String loginName) {
        String failKey = RedisKeyConstant.authLoginFailKey(loginName);
        Long failCount = stringRedisTemplate.opsForValue().increment(failKey);
        if (failCount == null) {
            throw new BusinessException(ErrorMessageConstant.AUTH_SERVICE_UNAVAILABLE);
        }

        Duration lockDuration = Duration.ofMinutes(authProperties.getLoginLockMinutes());
        if (failCount == 1L) {
            stringRedisTemplate.expire(failKey, lockDuration);
        }
        if (failCount >= authProperties.getLoginFailThreshold()) {
            stringRedisTemplate.opsForValue().set(RedisKeyConstant.authLoginLockKey(loginName), "1", lockDuration);
        }
    }

    public void clearFailure(String loginName) {
        stringRedisTemplate.delete(RedisKeyConstant.authLoginFailKey(loginName));
        stringRedisTemplate.delete(RedisKeyConstant.authLoginLockKey(loginName));
    }
}
