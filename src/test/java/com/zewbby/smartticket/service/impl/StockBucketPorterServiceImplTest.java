package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.dto.StockBucketPorterResult;
import com.zewbby.smartticket.domain.entity.TicketStockBucket;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockBucketPorterServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private TicketStockBucketMapper ticketStockBucketMapper;

    @Mock
    private ObservabilityMetricsService observabilityMetricsService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private StockBucketPorterServiceImpl service;

    @BeforeEach
    void setUp() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setPorterLockTtlSeconds(30);
        properties.setPorterMoveRecordTtlSeconds(86400);
        properties.setPorterMaxMoveQuantityPerRun(4);
        properties.setTailBucketCount(4);
        service = new StockBucketPorterServiceImpl(
                stringRedisTemplate,
                ticketStockBucketMapper,
                properties,
                observabilityMetricsService,
                transactionTemplate
        );
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @Test
    void moveReturnedStockMovesSourceVersionInventoryIntoTargetVersionBuckets() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RedisKeyConstant.stockBucketPorterLockKey(2L, 1, 2)),
                anyString(),
                eq(Duration.ofSeconds(30))
        )).thenReturn(true);
        when(ticketStockBucketMapper.countByTicketCategoryIdAndVersion(2L, 2)).thenReturn(4);
        when(ticketStockBucketMapper.selectByTicketCategoryIdAndVersion(2L, 1))
                .thenReturn(List.of(bucket(0, 3), bucket(1, 2), bucket(2, 0)));
        when(ticketStockBucketMapper.adjustAvailableStockByVersion(2L, 1, 0, -3)).thenReturn(1);
        when(ticketStockBucketMapper.adjustAvailableStockByVersion(2L, 2, 0, 3)).thenReturn(1);
        when(ticketStockBucketMapper.adjustAvailableStockByVersion(2L, 1, 1, -1)).thenReturn(1);
        when(ticketStockBucketMapper.adjustAvailableStockByVersion(2L, 2, 1, 1)).thenReturn(1);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString()
        )).thenReturn(1L);

        StockBucketPorterResult result = service.moveReturnedStock(2L, 1, 2, 10, 4, 4);

        assertThat(result.isLockAcquired()).isTrue();
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.getMovedBucketCount()).isEqualTo(2);
        assertThat(result.getMovedQuantity()).isEqualTo(4);
        verify(ticketStockBucketMapper).adjustAvailableStockByVersion(2L, 1, 0, -3);
        verify(ticketStockBucketMapper).adjustAvailableStockByVersion(2L, 2, 0, 3);
        verify(ticketStockBucketMapper).adjustAvailableStockByVersion(2L, 1, 1, -1);
        verify(ticketStockBucketMapper).adjustAvailableStockByVersion(2L, 2, 1, 1);
        verify(observabilityMetricsService).recordStockBucketPorterMoved(4);
    }

    @Test
    void moveReturnedStockSkipsWhenDistributedLockIsHeld() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RedisKeyConstant.stockBucketPorterLockKey(2L, 1, 2)),
                anyString(),
                eq(Duration.ofSeconds(30))
        )).thenReturn(false);

        StockBucketPorterResult result = service.moveReturnedStock(2L, 1, 2, 10, 4, 4);

        assertThat(result.isLockAcquired()).isFalse();
        assertThat(result.getMovedQuantity()).isZero();
        verify(ticketStockBucketMapper, never()).selectByTicketCategoryIdAndVersion(any(), any());
        verify(observabilityMetricsService).recordStockBucketPorterLockSkipped();
    }

    @Test
    void moveReturnedStockFailsWhenTargetVersionBucketsAreNotReady() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RedisKeyConstant.stockBucketPorterLockKey(2L, 1, 2)),
                anyString(),
                eq(Duration.ofSeconds(30))
        )).thenReturn(true);
        when(ticketStockBucketMapper.countByTicketCategoryIdAndVersion(2L, 2)).thenReturn(3);
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString()
        )).thenReturn(1L);

        assertThatThrownBy(() -> service.moveReturnedStock(2L, 1, 2, 10, 4, 4))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标版本bucket未初始化");

        verify(observabilityMetricsService).recordStockBucketPorterFailed();
    }

    private TicketStockBucket bucket(Integer bucketNo, Integer availableStock) {
        TicketStockBucket bucket = new TicketStockBucket();
        bucket.setTicketCategoryId(2L);
        bucket.setBucketVersion(1);
        bucket.setBucketNo(bucketNo);
        bucket.setAvailableStock(availableStock);
        return bucket;
    }
}
