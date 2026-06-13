package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockBucketSizingServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StockBucketProperties properties;

    private StockBucketSizingService service;

    @BeforeEach
    void setUp() {
        properties = new StockBucketProperties();
        properties.setDefaultBucketCount(10);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new StockBucketSizingService(stringRedisTemplate, properties);
    }

    @Test
    void versionedBucketCountHasHighestPriority() {
        when(valueOperations.get(RedisKeyConstant.stockBucketCountKey(2L, 1))).thenReturn("128");

        assertThat(service.resolveBucketCount(2L, 1)).isEqualTo(128);
    }

    @Test
    void legacyBucketCountIsCompatibilityFallback() {
        when(valueOperations.get(RedisKeyConstant.stockBucketCountKey(2L, 1))).thenReturn(null);
        when(valueOperations.get(RedisKeyConstant.stockBucketCountKey(2L))).thenReturn("64");

        assertThat(service.resolveBucketCount(2L, 1)).isEqualTo(64);
    }

    @Test
    void defaultBucketCountIsUsedWhenRedisHasNoValidConfiguration() {
        when(valueOperations.get(RedisKeyConstant.stockBucketCountKey(2L, 1))).thenReturn("0");
        when(valueOperations.get(RedisKeyConstant.stockBucketCountKey(2L))).thenReturn("not-number");

        assertThat(service.resolveBucketCount(2L, 1)).isEqualTo(10);
    }
}
