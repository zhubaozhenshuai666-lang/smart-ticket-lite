package com.zewbby.smartticket.auth;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class TokenBlacklistService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final StringRedisTemplate stringRedisTemplate;

    public TokenBlacklistService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 使token失效
     * @param claims
     */
    public void blacklist(JwtUserClaims claims) {
        //计算token剩余有效时间
        long ttlSeconds = Duration.between(Instant.now(), claims.getExpireAt()).getSeconds();
        //如果已经失效了，就抛已过期的异常
        if (ttlSeconds <= 0) {
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_EXPIRED);
        }

        try {
            stringRedisTemplate.opsForValue().set(
                    //设置黑名单的key
                    RedisKeyConstant.authTokenBlacklistKey(claims.getJti()),
                    "1",
                    Duration.ofSeconds(ttlSeconds)
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to write JWT blacklist, jti={}", claims.getJti(), exception);
            throw new BusinessException(401, ErrorMessageConstant.AUTH_SERVICE_UNAVAILABLE);
        }
    }

    public boolean isBlacklisted(String jti) {
        try {
            //如果redis存的黑名单key列表有这样的key的话就
            return stringRedisTemplate.hasKey(RedisKeyConstant.authTokenBlacklistKey(jti));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to check JWT blacklist, jti={}", jti, exception);
            throw new BusinessException(401, ErrorMessageConstant.TOKEN_INVALID);
        }
    }
}
