package com.zewbby.smartticket.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CacheService {

    public static final String NULL_VALUE = "__SMART_TICKET_NULL__";

    private final RedisTemplate<String, Object> redisTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    private final DefaultRedisScript<Long> lockReleaseScript;

    public CacheService(RedisTemplate<String, Object> redisTemplate,
                        StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockReleaseScript = buildScript("lua/redis_lock_release.lua");
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        Object value = getRaw(key);
        if (isNullValue(value)) {
            return null;
        }
        return (T) value;
    }

    public Object getRaw(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    public boolean isNullValue(Object value) {
        return NULL_VALUE.equals(value);
    }

    public void set(String key, Object value, Duration ttl) {
        redisTemplate.opsForValue().set(key, value, ttl);
    }

    public void set(String key, Object value, Duration ttl, Duration jitter) {
        set(key, value, withJitter(ttl, jitter));
    }

    public void setNullValue(String key, Duration ttl, Duration jitter) {
        set(key, NULL_VALUE, ttl, jitter);
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }

    public boolean tryLock(String key, String token, Duration ttl) {
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(locked);
    }

    public boolean releaseLock(String key, String token) {
        Long result = stringRedisTemplate.execute(lockReleaseScript, Collections.singletonList(key), token);
        return result != null && result == 1L;
    }

    public Duration withJitter(Duration ttl, Duration jitter) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (jitter == null || jitter.isNegative() || jitter.isZero()) {
            return ttl;
        }
        long jitterMillis = jitter.toMillis();
        if (jitterMillis <= 0L) {
            return ttl;
        }
        long extraMillis = ThreadLocalRandom.current().nextLong(jitterMillis + 1L);
        return ttl.plusMillis(extraMillis);
    }

    private DefaultRedisScript<Long> buildScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(Long.class);
        return script;
    }
}
