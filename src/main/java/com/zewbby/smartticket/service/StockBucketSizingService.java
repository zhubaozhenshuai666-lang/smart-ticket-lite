package com.zewbby.smartticket.service;

import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class StockBucketSizingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockBucketSizingService.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final StockBucketProperties stockBucketProperties;

    public StockBucketSizingService(StringRedisTemplate stringRedisTemplate,
                                    StockBucketProperties stockBucketProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.stockBucketProperties = stockBucketProperties;
    }

    public int resolveBucketCount(Long ticketCategoryId, Integer bucketVersion) {
        Integer configuredCount = readConfiguredBucketCount(ticketCategoryId, bucketVersion);
        if (configuredCount != null && configuredCount > 0) {
            return configuredCount;
        }
        return Math.max(1, stockBucketProperties.getDefaultBucketCount());
    }

    private Integer readConfiguredBucketCount(Long ticketCategoryId, Integer bucketVersion) {
        if (ticketCategoryId == null) {
            return null;
        }
        String versionedKey = RedisKeyConstant.stockBucketCountKey(ticketCategoryId, bucketVersion);
        Integer versionedCount = readPositiveInt(versionedKey);
        if (versionedCount != null) {
            return versionedCount;
        }
        return readPositiveInt(RedisKeyConstant.stockBucketCountKey(ticketCategoryId));
    }

    private Integer readPositiveInt(String key) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return null;
            }
            int parsed = parsePositiveInt(key, value);
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to read stock bucket count, key={}", key, exception);
            return null;
        }
    }

    private int parsePositiveInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            LOGGER.warn("Ignored invalid stock bucket count, key={}, value={}", key, value);
            return -1;
        }
    }
}
