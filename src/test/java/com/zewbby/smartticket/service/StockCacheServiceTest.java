package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockCacheServiceTest {

    @Mock
    private TicketStockMapper ticketStockMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private StockCacheService stockCacheService;

    @BeforeEach
    void setUp() {
        stockCacheService = new StockCacheService(ticketStockMapper, stringRedisTemplate);
    }

    @Test
    void preloadStockLoadsMysqlAvailableStockIntoRedisAndClearsSoldoutMarker() {
        when(ticketStockMapper.selectByTicketCategoryId(2L))
                .thenReturn(new TicketStock(1L, 2L, 10, 8, 1, 1, 0, null, null));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        Integer redisStock = stockCacheService.preloadStock(2L);

        assertThat(redisStock).isEqualTo(8);
        verify(valueOperations).set("ticket:stock:2", "8");
        verify(stringRedisTemplate).delete("ticket:soldout:2");
    }

    @Test
    void isSoldOutReturnsTrueWhenSoldoutMarkerExists() {
        when(stringRedisTemplate.hasKey("ticket:soldout:2")).thenReturn(true);

        assertThat(stockCacheService.isSoldOut(2L)).isTrue();
    }

    @Test
    void clearSoldoutIfStockPositiveDeletesMarkerOnlyWhenStockRestored() {
        stockCacheService.clearSoldoutIfStockPositive(2L, 1);
        stockCacheService.clearSoldoutIfStockPositive(3L, 0);

        verify(stringRedisTemplate).delete("ticket:soldout:2");
        verify(stringRedisTemplate, never()).delete("ticket:soldout:3");
    }
}
