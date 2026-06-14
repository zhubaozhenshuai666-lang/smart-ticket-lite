package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.domain.entity.StockCompensationRecord;
import com.zewbby.smartticket.domain.entity.StockConsistencyRecord;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.enums.CompensationStatusEnum;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.enums.RedisStockRepairResult;
import com.zewbby.smartticket.enums.StockCompensationStatusEnum;
import com.zewbby.smartticket.enums.StockConsistencyRecordStatusEnum;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.StockCompensationRecordMapper;
import com.zewbby.smartticket.mapper.StockConsistencyRecordMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.service.StockLuaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockConsistencyServiceImplTest {

    @Mock
    private TicketStockMapper ticketStockMapper;

    @Mock
    private OrderRequestMapper orderRequestMapper;

    @Mock
    private StockConsistencyRecordMapper consistencyRecordMapper;

    @Mock
    private StockCompensationRecordMapper compensationRecordMapper;

    @Mock
    private StockCacheService stockCacheService;

    @Mock
    private StockLuaService stockLuaService;

    private StockConsistencyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StockConsistencyServiceImpl(
                ticketStockMapper,
                orderRequestMapper,
                consistencyRecordMapper,
                compensationRecordMapper,
                stockCacheService,
                stockLuaService
        );
    }

    @Test
    void redisEqualsExpectedDoesNotCreateConsistencyRecord() {
        mockSnapshot(100, 1, 99);

        var result = service.checkOne(2L, "MANUAL");

        assertThat(result.getInFlightDeductedQuantity()).isEqualTo(1);
        assertThat(result.getExpectedRedisAvailableStock()).isEqualTo(99);
        assertThat(result.getRedisExpectedConsistent()).isTrue();
        assertThat(result.getConsistencyRecordId()).isNull();
        verify(consistencyRecordMapper, never()).insert(any());
    }

    @Test
    void redisDiffersFromMysqlButEqualsExpectedDoesNotCreateRecord() {
        mockSnapshot(100, 3, 97);

        var result = service.checkOne(2L, "MANUAL");

        assertThat(result.getMysqlAvailableStock()).isEqualTo(100);
        assertThat(result.getRedisAvailableStock()).isEqualTo(97);
        assertThat(result.getExpectedRedisAvailableStock()).isEqualTo(97);
        verify(consistencyRecordMapper, never()).insert(any());
    }

    @Test
    void redisLessThanExpectedCreatesPendingRecord() {
        mockSnapshot(100, 2, 97);
        when(consistencyRecordMapper.insert(any())).thenAnswer(invocation -> {
            StockConsistencyRecord record = invocation.getArgument(0);
            record.setId(88L);
            return 1;
        });

        var result = service.checkOne(2L, "MANUAL");

        assertThat(result.getExpectedRedisAvailableStock()).isEqualTo(98);
        assertThat(result.getDiff()).isEqualTo(-1);
        assertThat(result.getConsistencyRecordId()).isEqualTo(88L);
        ArgumentCaptor<StockConsistencyRecord> captor = ArgumentCaptor.forClass(StockConsistencyRecord.class);
        verify(consistencyRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StockConsistencyRecordStatusEnum.PENDING.getCode());
    }

    @Test
    void missingRedisKeyCreatesPendingRecord() {
        mockSnapshot(100, 0, null);

        service.checkOne(2L, "MANUAL");

        verify(consistencyRecordMapper).insert(any(StockConsistencyRecord.class));
    }

    @Test
    void checkAllScansTicketStockByPage() {
        TicketStock first = stock(1L, 1L, 10, 0, 0);
        TicketStock second = stock(2L, 2L, 20, 0, 0);
        when(ticketStockMapper.selectPageAfterId(0L, 2)).thenReturn(List.of(first, second));
        when(ticketStockMapper.selectPageAfterId(2L, 2)).thenReturn(List.of());
        when(orderRequestMapper.sumInFlightDeductedQuantity(anyLong())).thenReturn(0);
        when(stockCacheService.getAvailableStock(1L)).thenReturn(10);
        when(stockCacheService.getAvailableStock(2L)).thenReturn(20);

        var result = service.checkAll("MANUAL", 2);

        assertThat(result).hasSize(2);
        verify(ticketStockMapper).selectPageAfterId(0L, 2);
        verify(ticketStockMapper).selectPageAfterId(2L, 2);
    }

    @Test
    void repairRecomputesExpectedAndUsesLuaCasDelta() {
        StockConsistencyRecord record = consistencyRecord(2L);
        when(consistencyRecordMapper.selectById(9L)).thenReturn(record);
        mockSnapshot(100, 2, 97);
        when(stockLuaService.repairStockByCasDelta(2L, 97, 1)).thenReturn(RedisStockRepairResult.SUCCESS);
        when(consistencyRecordMapper.markRepaired(eq(9L), any(), any(), any(LocalDateTime.class))).thenReturn(1);

        service.repairRecord(9L);

        verify(stockLuaService).repairStockByCasDelta(2L, 97, 1);
        verify(stockCacheService).clearSoldoutIfStockPositive(2L, 98);
        verify(compensationRecordMapper).insert(any(StockCompensationRecord.class));
        verify(consistencyRecordMapper).markRepaired(eq(9L), any(), any(), any(LocalDateTime.class));
    }

    @Test
    void repairDoesNotOverwriteWhenRedisWasConcurrentlyModified() {
        StockConsistencyRecord record = consistencyRecord(2L);
        when(consistencyRecordMapper.selectById(9L)).thenReturn(record);
        mockSnapshot(100, 2, 97);
        when(stockLuaService.repairStockByCasDelta(2L, 97, 1)).thenReturn(RedisStockRepairResult.CONCURRENT_MODIFIED);
        when(stockCacheService.getAvailableStock(2L)).thenReturn(97, 96);
        when(consistencyRecordMapper.markFailed(eq(9L), any(), any())).thenReturn(1);

        service.repairRecord(9L);

        ArgumentCaptor<StockCompensationRecord> captor = ArgumentCaptor.forClass(StockCompensationRecord.class);
        verify(compensationRecordMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StockCompensationStatusEnum.CONCURRENT_MODIFIED.getCode());
        verify(consistencyRecordMapper).markFailed(eq(9L), any(), any());
    }

    @Test
    void failedRequestCompensationReleasesRedisPreDeductedStock() {
        TicketOrderRequest request = failedRequest();
        when(orderRequestMapper.selectFailedRequestsNeedCompensation(10)).thenReturn(List.of(request));
        when(orderRequestMapper.tryMarkCompensating(10L)).thenReturn(1);
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(stock(1L, 2L, 100, 0, 0));
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(0);
        when(stockCacheService.getAvailableStock(2L)).thenReturn(97, 97, 98);
        when(stockLuaService.releasePreDeductedStock("REQ1", 2L, null, null, 1)).thenReturn(RedisStockReleaseResult.SUCCESS);
        when(orderRequestMapper.markCompensated(eq(10L), any(LocalDateTime.class))).thenReturn(1);

        int count = service.compensateFailedRequests(10);

        assertThat(count).isEqualTo(1);
        verify(stockLuaService).releasePreDeductedStock("REQ1", 2L, null, null, 1);
        verify(stockCacheService).clearSoldoutIfStockPositive(2L, 98);
        verify(orderRequestMapper).markCompensated(eq(10L), any(LocalDateTime.class));
        verify(compensationRecordMapper).insert(any(StockCompensationRecord.class));
    }

    @Test
    void failedRequestRepeatedCompensationIsSkippedWhenClaimFails() {
        TicketOrderRequest request = failedRequest();
        when(orderRequestMapper.selectFailedRequestsNeedCompensation(10)).thenReturn(List.of(request));
        when(orderRequestMapper.tryMarkCompensating(10L)).thenReturn(0);

        int count = service.compensateFailedRequests(10);

        assertThat(count).isZero();
        verify(stockLuaService, never()).releasePreDeductedStock(any(), anyLong(), any(), any(), anyInt());
    }

    private void mockSnapshot(Integer mysqlAvailable, Integer inFlight, Integer redisAvailable) {
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(stock(1L, 2L, mysqlAvailable, 0, 0));
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(inFlight);
        when(stockCacheService.getAvailableStock(2L)).thenReturn(redisAvailable);
    }

    private TicketStock stock(Long id, Long ticketCategoryId, Integer available, Integer locked, Integer sold) {
        TicketStock stock = new TicketStock();
        stock.setId(id);
        stock.setTicketCategoryId(ticketCategoryId);
        stock.setTotalStock(available + locked + sold);
        stock.setAvailableStock(available);
        stock.setLockedStock(locked);
        stock.setSoldStock(sold);
        return stock;
    }

    private StockConsistencyRecord consistencyRecord(Long ticketCategoryId) {
        StockConsistencyRecord record = new StockConsistencyRecord();
        record.setId(9L);
        record.setTicketCategoryId(ticketCategoryId);
        record.setStatus(StockConsistencyRecordStatusEnum.PENDING.getCode());
        return record;
    }

    private TicketOrderRequest failedRequest() {
        TicketOrderRequest request = new TicketOrderRequest();
        request.setId(10L);
        request.setRequestId("REQ1");
        request.setTicketCategoryId(2L);
        request.setStatus(OrderRequestStatusEnum.FAILED.getCode());
        request.setRedisDeducted(true);
        request.setDeductedQuantity(1);
        request.setCompensated(false);
        request.setCompensationStatus(CompensationStatusEnum.NONE.getCode());
        return request;
    }
}
