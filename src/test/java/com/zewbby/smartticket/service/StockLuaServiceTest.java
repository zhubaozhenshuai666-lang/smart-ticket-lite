package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.RedisStockDeductResponse;
import com.zewbby.smartticket.enums.RedisStockDeductResult;
import com.zewbby.smartticket.enums.RedisStockRepairResult;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockLuaServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StockLuaService stockLuaService;

    @BeforeEach
    void setUp() {
        stockLuaService = new StockLuaService(stringRedisTemplate);
    }

    @Test
    void preDeductStockReturnsSuccessWhenLuaReturnsSuccess() {
        mockPreDeductLuaResult(1L);

        RedisStockDeductResult result = stockLuaService.preDeductStock("REQ1", 2L, 1);

        assertThat(result).isEqualTo(RedisStockDeductResult.SUCCESS);
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void preDeductStockReturnsStockNotEnoughAndSetsSoldoutMarker() {
        mockPreDeductLuaResult(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisStockDeductResult result = stockLuaService.preDeductStock("REQ1", 2L, 1);

        assertThat(result).isEqualTo(RedisStockDeductResult.STOCK_NOT_ENOUGH);
        verify(valueOperations).set("ticket:soldout:2", "1", Duration.ofSeconds(600));
    }

    @Test
    void preDeductStockReturnsStockNotFoundWhenStockKeyMissing() {
        mockPreDeductLuaResult(-1L);

        RedisStockDeductResult result = stockLuaService.preDeductStock("REQ1", 2L, 1);

        assertThat(result).isEqualTo(RedisStockDeductResult.STOCK_NOT_FOUND);
    }

    @Test
    void preDeductStockReturnsInvalidQuantityWhenLuaRejectsQuantity() {
        mockPreDeductLuaResult(-3L);

        RedisStockDeductResult result = stockLuaService.preDeductStock("REQ1", 2L, 0);

        assertThat(result).isEqualTo(RedisStockDeductResult.INVALID_QUANTITY);
    }

    @Test
    void preDeductStockReturnsDuplicateForSameRequestId() {
        mockPreDeductLuaResult(-2L);

        RedisStockDeductResult result = stockLuaService.preDeductStock("REQ1", 2L, 1);

        assertThat(result).isEqualTo(RedisStockDeductResult.DUPLICATE);
    }

    @Test
    void preDeductBucketStockReturnsActualBucketNoFromDeductedRecord() {
        mockBucketPreDeductLuaResult(1L, 3);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("ticket:stock:deducted:REQ1")).thenReturn("2:v1:2:1");

        RedisStockDeductResponse response = stockLuaService.preDeductBucketStock("REQ1", 2L, 1, 0, 3);

        assertThat(response.getResult()).isEqualTo(RedisStockDeductResult.SUCCESS);
        assertThat(response.getBucketNo()).isEqualTo(2);
    }

    @Test
    void preDeductBucketStockReturnsStockNotEnoughWhenAllBucketsAreShort() {
        mockBucketPreDeductLuaResult(0L, 3);

        RedisStockDeductResponse response = stockLuaService.preDeductBucketStock("REQ1", 2L, 99, 0, 3);

        assertThat(response.getResult()).isEqualTo(RedisStockDeductResult.STOCK_NOT_ENOUGH);
        assertThat(response.getBucketNo()).isNull();
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void preDeductBucketStockReturnsProbeMissWhenSmallProbeWindowMisses() {
        mockBucketPreDeductLuaResult(2L, 10);

        RedisStockDeductResponse response = stockLuaService.preDeductBucketStock("REQ1", 2L, 99, 0, 10);

        assertThat(response.getResult()).isEqualTo(RedisStockDeductResult.PROBE_MISS);
        assertThat(response.getBucketNo()).isNull();
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void preDeductBucketStockReturnsDuplicateForRepeatedRequestId() {
        mockBucketPreDeductLuaResult(-2L, 3);

        RedisStockDeductResponse response = stockLuaService.preDeductBucketStock("REQ1", 2L, 1, 0, 3);

        assertThat(response.getResult()).isEqualTo(RedisStockDeductResult.DUPLICATE);
        assertThat(response.getBucketNo()).isNull();
    }

    @Test
    void releasePreDeductedStockReturnsSuccess() {
        mockReleaseLuaResult(1L);

        RedisStockReleaseResult result = stockLuaService.releasePreDeductedStock("REQ1", 2L, 1);

        assertThat(result).isEqualTo(RedisStockReleaseResult.SUCCESS);
    }

    @Test
    void releasePreDeductedStockReturnsAlreadyCompensatedForRepeatedRelease() {
        mockReleaseLuaResult(-1L);

        RedisStockReleaseResult result = stockLuaService.releasePreDeductedStock("REQ1", 2L, 1);

        assertThat(result).isEqualTo(RedisStockReleaseResult.ALREADY_COMPENSATED);
    }

    @Test
    void releasePreDeductedBucketStockUsesOriginalBucket() {
        mockBucketReleaseLuaResult(1L, 2);

        RedisStockReleaseResult result = stockLuaService.releasePreDeductedStock("REQ1", 2L, 2, 1);

        assertThat(result).isEqualTo(RedisStockReleaseResult.SUCCESS);
    }

    @Test
    void releasePreDeductedBucketStockDoesNotCompensateTwice() {
        mockBucketReleaseLuaResult(-1L, 2);

        RedisStockReleaseResult result = stockLuaService.releasePreDeductedStock("REQ1", 2L, 2, 1);

        assertThat(result).isEqualTo(RedisStockReleaseResult.ALREADY_COMPENSATED);
    }

    @Test
    void releasePreDeductedBucketStockSupportsVersionedBucket() {
        mockVersionedBucketReleaseLuaResult(1L, 1, 2);

        RedisStockReleaseResult result = stockLuaService.releasePreDeductedStock("REQ1", 2L, 1, 2, 1);

        assertThat(result).isEqualTo(RedisStockReleaseResult.SUCCESS);
    }

    @Test
    void repairStockByCasDeltaReturnsConcurrentModifiedWhenLuaRejectsStaleBeforeValue() {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(java.util.Collections.singletonList("ticket:stock:2")),
                eq("97"),
                eq("1")
        )).thenReturn(-2L);

        RedisStockRepairResult result = stockLuaService.repairStockByCasDelta(2L, 97, 1);

        assertThat(result).isEqualTo(RedisStockRepairResult.CONCURRENT_MODIFIED);
    }

    private void mockPreDeductLuaResult(Long result) {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Arrays.asList("ticket:stock:2", "ticket:stock:deducted:REQ1", "ticket:soldout:2")),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(result);
    }

    private void mockReleaseLuaResult(Long result) {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Arrays.asList("ticket:stock:2", "ticket:stock:deducted:REQ1", "ticket:stock:compensated:REQ1")),
                anyString(),
                anyString()
        )).thenReturn(result);
    }

    private void mockBucketPreDeductLuaResult(Long result, int bucketCount) {
        int attemptLimit = Math.min(3, bucketCount);
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add("ticket:stock:deducted:REQ1");
        keys.add("ticket:soldout:2:v1");
        for (int bucketNo = 0; bucketNo < attemptLimit; bucketNo++) {
            keys.add("ticket:stock:2:v1:bucket:" + bucketNo);
        }
        for (int bucketNo = 0; bucketNo < attemptLimit; bucketNo++) {
            keys.add("ticket:soldout:2:v1:bucket:" + bucketNo);
        }
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(keys),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(result);
    }

    private void mockBucketReleaseLuaResult(Long result, int bucketNo) {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Arrays.asList(
                        "ticket:stock:2:bucket:" + bucketNo,
                        "ticket:stock:deducted:REQ1",
                        "ticket:stock:compensated:REQ1",
                        "ticket:soldout:2:bucket:" + bucketNo,
                        "ticket:soldout:2"
                )),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(result);
    }

    private void mockVersionedBucketReleaseLuaResult(Long result, int bucketVersion, int bucketNo) {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(Arrays.asList(
                        "ticket:stock:2:v" + bucketVersion + ":bucket:" + bucketNo,
                        "ticket:stock:deducted:REQ1",
                        "ticket:stock:compensated:REQ1",
                        "ticket:soldout:2:v" + bucketVersion + ":bucket:" + bucketNo,
                        "ticket:soldout:2:v" + bucketVersion
                )),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(result);
    }
}
