package com.zewbby.smartticket.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitService.class);

    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean tryAcquire(String key, int limit, long windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("limit and windowSeconds must be positive");
        }

        try {
            //计数器加一
            Long current = stringRedisTemplate.opsForValue().increment(key);
            //如果没有这个key的话就报错
            if (current == null) {
                LOGGER.warn("Redis rate limit increment returned null, key={}", key);
                return true;
            }

            //第一次放进来要加一个时间窗口
            if (current == 1L) {
                stringRedisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
            }

            return current <= limit;
            //如果redis崩了，就放行
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis限流降级并允许请求, key={}", key, exception);
            return true;
        }
    }
}
