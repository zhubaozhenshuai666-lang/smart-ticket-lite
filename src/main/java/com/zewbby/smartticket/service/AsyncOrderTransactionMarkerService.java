package com.zewbby.smartticket.service;

import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.dto.AsyncOrderTransactionMarker;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AsyncOrderTransactionMarkerService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncOrderTransactionMarkerService.class);

    private static final Duration MARKER_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;

    private final StringRedisTemplate stringRedisTemplate;

    public AsyncOrderTransactionMarkerService(RedisTemplate<String, Object> redisTemplate,
                                              StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void save(TicketOrderRequest orderRequest, AsyncCreateOrderMessage message) {
        if (orderRequest == null || orderRequest.getRequestId() == null) {
            return;
        }
        AsyncOrderTransactionMarker marker = new AsyncOrderTransactionMarker();
        marker.setRequestId(orderRequest.getRequestId());
        marker.setUserId(orderRequest.getUserId());
        marker.setShowId(orderRequest.getShowId());
        marker.setSessionId(orderRequest.getSessionId());
        marker.setTicketCategoryId(orderRequest.getTicketCategoryId());
        marker.setQuantity(orderRequest.getQuantity());
        marker.setStockBucketVersion(orderRequest.getStockBucketVersion());
        marker.setStockBucketNo(orderRequest.getStockBucketNo());
        marker.setRedisDeducted(orderRequest.getRedisDeducted());
        marker.setDeductedQuantity(orderRequest.getDeductedQuantity());
        marker.setDeductedAt(orderRequest.getDeductedAt());
        marker.setMessageId(orderRequest.getMessageId());
        marker.setActivityScopeKey(message == null ? null : message.getActivityScopeKey());
        marker.setRoutingPartitionKey(message == null ? null : message.getRoutingPartitionKey());
        marker.setCreatedAt(LocalDateTime.now());
        redisTemplate.opsForValue().set(
                RedisKeyConstant.asyncOrderTransactionMarkerKey(orderRequest.getRequestId()),
                marker,
                MARKER_TTL
        );
    }

    public AsyncOrderTransactionMarker load(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        Object value = redisTemplate.opsForValue()
                .get(RedisKeyConstant.asyncOrderTransactionMarkerKey(requestId));
        if (value instanceof AsyncOrderTransactionMarker marker) {
            return marker;
        }
        return null;
    }

    public boolean hasCommittedDeduction(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return false;
        }
        return hasDeductedRecord(requestId);
    }

    public AsyncCreateOrderMessage enrichMessage(AsyncCreateOrderMessage message) {
        if (message == null || message.getRequestId() == null) {
            return message;
        }
        AsyncOrderTransactionMarker marker = loadOrRecover(message);
        if (marker == null) {
            return message;
        }
        message.setUserId(marker.getUserId());
        message.setShowId(marker.getShowId());
        message.setSessionId(marker.getSessionId());
        message.setTicketCategoryId(marker.getTicketCategoryId());
        message.setQuantity(marker.getQuantity());
        message.setStockBucketVersion(marker.getStockBucketVersion());
        message.setStockBucketNo(marker.getStockBucketNo());
        message.setRedisDeducted(marker.getRedisDeducted());
        message.setDeductedQuantity(marker.getDeductedQuantity());
        message.setDeductedAt(marker.getDeductedAt());
        message.setMessageId(marker.getMessageId());
        message.setActivityScopeKey(marker.getActivityScopeKey());
        message.setRoutingPartitionKey(marker.getRoutingPartitionKey());
        return message;
    }

    private AsyncOrderTransactionMarker loadOrRecover(AsyncCreateOrderMessage message) {
        if (!hasDeductedRecord(message.getRequestId())) {
            return null;
        }
        AsyncOrderTransactionMarker marker = load(message.getRequestId());
        if (marker != null) {
            return marker;
        }
        String deductedValue = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstant.stockDeductedRequestKey(message.getRequestId()));
        if (deductedValue == null || deductedValue.isBlank()) {
            return null;
        }
        AsyncOrderTransactionMarker recovered = recoverFromDeductedRecord(message, deductedValue);
        try {
            redisTemplate.opsForValue().set(
                    RedisKeyConstant.asyncOrderTransactionMarkerKey(recovered.getRequestId()),
                    recovered,
                    MARKER_TTL
            );
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to backfill async order transaction marker, requestId={}",
                    recovered.getRequestId(), exception);
        }
        return recovered;
    }

    private boolean hasDeductedRecord(String requestId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisKeyConstant.stockDeductedRequestKey(requestId)));
    }

    private AsyncOrderTransactionMarker recoverFromDeductedRecord(AsyncCreateOrderMessage message,
                                                                 String deductedValue) {
        DeductedRecord record = parseDeductedRecord(message, deductedValue);
        AsyncOrderTransactionMarker marker = new AsyncOrderTransactionMarker();
        marker.setRequestId(message.getRequestId());
        marker.setUserId(message.getUserId());
        marker.setShowId(message.getShowId());
        marker.setSessionId(message.getSessionId());
        marker.setTicketCategoryId(message.getTicketCategoryId());
        marker.setQuantity(message.getQuantity());
        marker.setStockBucketVersion(record.stockBucketVersion());
        marker.setStockBucketNo(record.stockBucketNo());
        marker.setRedisDeducted(true);
        marker.setDeductedQuantity(record.quantity());
        marker.setDeductedAt(message.getDeductedAt() == null ? LocalDateTime.now() : message.getDeductedAt());
        marker.setMessageId(message.getMessageId());
        marker.setActivityScopeKey(message.getActivityScopeKey());
        marker.setRoutingPartitionKey(message.getRoutingPartitionKey());
        marker.setCreatedAt(LocalDateTime.now());
        return marker;
    }

    private DeductedRecord parseDeductedRecord(AsyncCreateOrderMessage message, String value) {
        String[] parts = value.split(":");
        if (parts.length == 1) {
            return new DeductedRecord(message.getStockBucketVersion(), null, parseInteger(parts[0], "quantity"));
        }
        if (parts.length >= 4 && parts[1].startsWith("v")) {
            return new DeductedRecord(
                    parseInteger(parts[1].substring(1), "stockBucketVersion"),
                    parseInteger(parts[2], "stockBucketNo"),
                    parseInteger(parts[3], "quantity")
            );
        }
        if (parts.length >= 3) {
            return new DeductedRecord(
                    message.getStockBucketVersion(),
                    parseInteger(parts[1], "stockBucketNo"),
                    parseInteger(parts[2], "quantity")
            );
        }
        throw new IllegalStateException("Redis预扣记录格式非法: " + value);
    }

    private Integer parseInteger(String value, String fieldName) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Redis预扣记录字段非法: " + fieldName, exception);
        }
    }

    private record DeductedRecord(Integer stockBucketVersion, Integer stockBucketNo, Integer quantity) {
    }
}
