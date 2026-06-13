package com.zewbby.smartticket.service;

import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class AsyncOrderRequestResultCacheService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncOrderRequestResultCacheService.class);

    private static final Duration QUEUED_RESULT_TTL = Duration.ofMinutes(5);

    private static final Duration TERMINAL_RESULT_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    public AsyncOrderRequestResultCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void cacheQueuedResult(Long userId, OrderRequestVO orderRequestVO) {
        if (userId == null || orderRequestVO == null || orderRequestVO.getRequestId() == null) {
            return;
        }
        try {
            cacheResult(userId, orderRequestVO, QUEUED_RESULT_TTL);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to cache async order queued result, userId={}, requestId={}",
                    userId, orderRequestVO.getRequestId(), exception);
        }
    }

    public void cacheTerminalResult(Long userId, OrderRequestVO orderRequestVO) {
        if (userId == null || orderRequestVO == null || orderRequestVO.getRequestId() == null) {
            return;
        }
        try {
            cacheResult(userId, orderRequestVO, TERMINAL_RESULT_TTL);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to cache async order terminal result, userId={}, requestId={}",
                    userId, orderRequestVO.getRequestId(), exception);
        }
    }

    public OrderRequestVO getQueuedResult(Long userId, String requestId) {
        return getCachedResult(userId, requestId);
    }

    public OrderRequestVO getCachedResult(Long userId, String requestId) {
        if (userId == null || requestId == null || requestId.isBlank()) {
            return null;
        }
        try {
            Object value = redisTemplate.opsForValue().get(RedisKeyConstant.asyncOrderResultKey(userId, requestId));
            return value instanceof OrderRequestVO orderRequestVO ? orderRequestVO : null;
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to get cached async order queued result, userId={}, requestId={}",
                    userId, requestId, exception);
            return null;
        }
    }

    private void cacheResult(Long userId, OrderRequestVO orderRequestVO, Duration ttl) {
        redisTemplate.opsForValue().set(
                RedisKeyConstant.asyncOrderResultKey(userId, orderRequestVO.getRequestId()),
                orderRequestVO,
                ttl
        );
    }
}
