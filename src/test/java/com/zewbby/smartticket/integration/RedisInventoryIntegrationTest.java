package com.zewbby.smartticket.integration;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.enums.RedisStockDeductResult;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.idempotency.IdempotencyTokenService;
import com.zewbby.smartticket.service.AdminBusinessService;
import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.service.StockLuaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisInventoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AdminBusinessService adminBusinessService;

    @Autowired
    private StockLuaService stockLuaService;

    @Autowired
    private StockCacheService stockCacheService;

    @Autowired
    private IdempotencyTokenService idempotencyTokenService;

    @Test
    void stockPreheatPreDeductReleaseSoldoutAndIdempotencyTokenRunAgainstRealRedisLua() {
        /*
         * 这条测试不 Mock RedisTemplate，而是让 Lua 真正在 Redis 容器里执行。
         * 目的不是证明 Java 方法被调用，而是证明 key 设计、Lua 返回码、原子扣减和补偿防重能在真实 Redis 协议下工作。
         */
        adminBusinessService.preheatStock(2L);
        assertThat(stockCacheService.getAvailableStock(2L)).isEqualTo(1000);

        RedisStockDeductResult firstDeduct = stockLuaService.preDeductStock("REQ-REDIS-1", 2L, 2);
        assertThat(firstDeduct).isEqualTo(RedisStockDeductResult.SUCCESS);
        assertThat(stockCacheService.getAvailableStock(2L)).isEqualTo(998);

        RedisStockDeductResult duplicateDeduct = stockLuaService.preDeductStock("REQ-REDIS-1", 2L, 2);
        assertThat(duplicateDeduct).isEqualTo(RedisStockDeductResult.DUPLICATE);
        assertThat(stockCacheService.getAvailableStock(2L)).isEqualTo(998);

        RedisStockDeductResult notEnough = stockLuaService.preDeductStock("REQ-REDIS-2", 2L, 9999);
        assertThat(notEnough).isEqualTo(RedisStockDeductResult.STOCK_NOT_ENOUGH);
        assertThat(stockCacheService.isSoldOut(2L)).isTrue();

        RedisStockReleaseResult release = stockLuaService.releasePreDeductedStock("REQ-REDIS-1", 2L, 2);
        assertThat(release).isEqualTo(RedisStockReleaseResult.SUCCESS);
        assertThat(stockCacheService.getAvailableStock(2L)).isEqualTo(1000);

        RedisStockReleaseResult duplicateRelease = stockLuaService.releasePreDeductedStock("REQ-REDIS-1", 2L, 2);
        assertThat(duplicateRelease).isEqualTo(RedisStockReleaseResult.ALREADY_COMPENSATED);
        assertThat(stockCacheService.getAvailableStock(2L)).isEqualTo(1000);

        adminBusinessService.preheatStock(2L);
        assertThat(stringRedisTemplate.hasKey(RedisKeyConstant.stockSoldoutKey(2L))).isFalse();

        String token = idempotencyTokenService.generateOrderToken(1L).getToken();
        idempotencyTokenService.consumeOrderToken(1L, token);
        assertThatThrownBy(() -> idempotencyTokenService.consumeOrderToken(1L, token))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请勿重复提交");
    }
}
