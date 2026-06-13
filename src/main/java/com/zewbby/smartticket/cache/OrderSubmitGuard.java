package com.zewbby.smartticket.cache;

import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OrderSubmitGuard {

    private static final Duration SUBMIT_GUARD_TTL = Duration.ofSeconds(10);

    private final RedisTemplate<String, Object> redisTemplate;

    public OrderSubmitGuard(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean tryAcquire(Long userId, Long ticketCategoryId) {
        //设置出key
        String key = RedisKeyConstant.orderSubmitKey(userId, ticketCategoryId);
        //set出key，如果存在的话就返回失败
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, "1", SUBMIT_GUARD_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    public void release(Long userId, Long ticketCategoryId) {
        redisTemplate.delete(RedisKeyConstant.orderSubmitKey(userId, ticketCategoryId));
    }
}
