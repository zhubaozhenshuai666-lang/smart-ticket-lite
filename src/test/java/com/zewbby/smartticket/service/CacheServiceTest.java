package com.zewbby.smartticket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, Object> objectValueOperations;

    @Mock
    private ValueOperations<String, String> stringValueOperations;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(redisTemplate, stringRedisTemplate);
    }

    @Test
    void getReturnsNullForNullValueMarker() {
        when(redisTemplate.opsForValue()).thenReturn(objectValueOperations);
        when(objectValueOperations.get("show:detail:404")).thenReturn(CacheService.NULL_VALUE);

        Object value = cacheService.get("show:detail:404");

        assertThat(value).isNull();
        assertThat(cacheService.isNullValue(CacheService.NULL_VALUE)).isTrue();
    }

    @Test
    void setWithJitterUsesTtlBetweenBaseAndUpperBound() {
        when(redisTemplate.opsForValue()).thenReturn(objectValueOperations);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

        cacheService.set("key", "value", Duration.ofSeconds(10), Duration.ofSeconds(5));

        verify(objectValueOperations).set(eq("key"), eq("value"), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    @Test
    void tryLockUsesRedisSetIfAbsentWithTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOperations);
        when(stringValueOperations.setIfAbsent("lock:key", "token", Duration.ofSeconds(5))).thenReturn(true);

        assertThat(cacheService.tryLock("lock:key", "token", Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void releaseLockExecutesTokenCheckedLuaScript() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), eq("token"))).thenReturn(1L);

        assertThat(cacheService.releaseLock("lock:key", "token")).isTrue();
    }
}
