package com.zewbby.smartticket.service;

import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class ActivityDegradeService {

    private final StringRedisTemplate stringRedisTemplate;

    public ActivityDegradeService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public boolean isOrderSubmitClosed(String activityScopeKey) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.activityDegradeClosedKey(activityScopeKey)));
    }

    public void closeOrderSubmit(String activityScopeKey, long ttlSeconds) {
        String key = RedisKeyConstant.activityDegradeClosedKey(activityScopeKey);
        if (ttlSeconds > 0) {
            stringRedisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
            return;
        }
        stringRedisTemplate.opsForValue().set(key, "1");
    }

    public void openOrderSubmit(String activityScopeKey) {
        stringRedisTemplate.delete(RedisKeyConstant.activityDegradeClosedKey(activityScopeKey));
    }
}
