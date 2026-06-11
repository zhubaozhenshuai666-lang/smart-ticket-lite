package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.domain.dto.AdminCreateSessionRequest;
import com.zewbby.smartticket.domain.dto.AdminCreateShowRequest;
import com.zewbby.smartticket.domain.dto.AdminCreateTicketCategoryRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateSessionRequest;
import com.zewbby.smartticket.domain.dto.AdminUpdateShowRequest;
import com.zewbby.smartticket.domain.dto.AdjustStockRequest;
import com.zewbby.smartticket.domain.dto.InitStockRequest;
import com.zewbby.smartticket.domain.entity.PerformanceSession;
import com.zewbby.smartticket.domain.entity.ShowInfo;
import com.zewbby.smartticket.domain.entity.TicketCategory;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.domain.entity.TicketStockBucket;
import com.zewbby.smartticket.domain.entity.Venue;
import com.zewbby.smartticket.enums.RedisStockRepairResult;
import com.zewbby.smartticket.enums.ShowStatusEnum;
import com.zewbby.smartticket.enums.TicketCategoryStatusEnum;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.ShowMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.VenueMapper;
import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.service.StockLuaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBusinessServiceImplTest {

    @Mock
    private VenueMapper venueMapper;

    @Mock
    private ShowMapper showMapper;

    @Mock
    private TicketCategoryMapper ticketCategoryMapper;

    @Mock
    private TicketStockMapper ticketStockMapper;

    @Mock
    private TicketStockBucketMapper ticketStockBucketMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderRequestMapper orderRequestMapper;

    @Mock
    private StockCacheService stockCacheService;

    @Mock
    private StockLuaService stockLuaService;

    private AdminBusinessServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AdminBusinessServiceImpl(
                venueMapper,
                showMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                orderMapper,
                orderRequestMapper,
                stockCacheService,
                stockLuaService
        );
    }

    @Test
    void createShowUsesDraftStatusAndValidatesVenue() {
        when(venueMapper.selectById(1L)).thenReturn(new Venue());
        AdminCreateShowRequest request = new AdminCreateShowRequest();
        request.setTitle("测试演出");
        request.setArtist("测试艺人");
        request.setVenueId(1L);
        request.setDescription("后台创建");

        service.createShow(request);

        ArgumentCaptor<ShowInfo> captor = ArgumentCaptor.forClass(ShowInfo.class);
        verify(showMapper).insertShow(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ShowStatusEnum.DRAFT.getCode());
        assertThat(captor.getValue().getVenueId()).isEqualTo(1L);
    }

    @Test
    void createSessionFailsWhenShowDoesNotExist() {
        AdminCreateSessionRequest request = new AdminCreateSessionRequest();
        request.setStartTime(LocalDateTime.now().plusDays(1));
        request.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));

        assertThatThrownBy(() -> service.createSession(99L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演出不存在");
    }

    @Test
    void createTicketCategoryFailsWhenSessionDoesNotExist() {
        AdminCreateTicketCategoryRequest request = new AdminCreateTicketCategoryRequest();
        request.setCategoryName("内场票");
        request.setPrice(BigDecimal.valueOf(880));

        assertThatThrownBy(() -> service.createTicketCategory(99L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("场次不存在");
    }

    @Test
    void createTicketCategoryRejectsNonPositivePrice() {
        when(showMapper.selectSessionById(1L)).thenReturn(new com.zewbby.smartticket.domain.entity.PerformanceSession());
        AdminCreateTicketCategoryRequest request = new AdminCreateTicketCategoryRequest();
        request.setCategoryName("内场票");
        request.setPrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createTicketCategory(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("票档价格必须大于0");
    }

    @Test
    void initStockInsertsNewStockAndClearsSoldoutThroughPreheat() {
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(0, 0, 0);
        TicketStock stock = stock(1L, 2L, 100, 0, 0);
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(null, stock, stock);
        when(stockCacheService.getAvailableStock(2L)).thenReturn(null, 100);
        InitStockRequest request = new InitStockRequest();
        request.setAvailableStock(100);

        var result = service.initStock(2L, request);

        verify(ticketStockMapper).insert(org.mockito.ArgumentMatchers.any(TicketStock.class));
        verify(stockCacheService).setAvailableStock(2L, 100);
        verify(stockCacheService).clearSoldout(2L);
        assertThat(result.getExpectedRedisAvailableStock()).isEqualTo(100);
    }

    @Test
    void initStockSplitsBucketEvenlyWhenBucketEnabled() {
        AdminBusinessServiceImpl bucketService = bucketEnabledService();
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(0, 0, 0);
        TicketStock stock = stock(1L, 2L, 10000, 0, 0);
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(null, stock, stock);
        when(ticketStockBucketMapper.countLockedOrSoldByTicketCategoryIdAndVersion(2L, 1)).thenReturn(0);
        when(ticketStockBucketMapper.selectByTicketCategoryIdAndVersion(2L, 1)).thenReturn(bucketList(2L,
                1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000));
        when(stockCacheService.sumBucketAvailableStock(2L, 1, 10)).thenReturn(10000);

        InitStockRequest request = new InitStockRequest();
        request.setAvailableStock(10000);

        bucketService.initStock(2L, request);

        ArgumentCaptor<TicketStockBucket> captor = ArgumentCaptor.forClass(TicketStockBucket.class);
        verify(ticketStockBucketMapper).deleteByTicketCategoryIdAndVersion(2L, 1);
        verify(ticketStockBucketMapper, org.mockito.Mockito.times(10)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TicketStockBucket::getAvailableStock)
                .containsExactly(1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000);
        assertThat(captor.getAllValues())
                .extracting(TicketStockBucket::getBucketVersion)
                .containsOnly(1);
    }

    @Test
    void initStockSplitsBucketRemainderToLeadingBuckets() {
        AdminBusinessServiceImpl bucketService = bucketEnabledService();
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(0, 0, 0);
        TicketStock stock = stock(1L, 2L, 10003, 0, 0);
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(null, stock, stock);
        when(ticketStockBucketMapper.countLockedOrSoldByTicketCategoryIdAndVersion(2L, 1)).thenReturn(0);
        when(ticketStockBucketMapper.selectByTicketCategoryIdAndVersion(2L, 1)).thenReturn(bucketList(2L,
                1001, 1001, 1001, 1000, 1000, 1000, 1000, 1000, 1000, 1000));
        when(stockCacheService.sumBucketAvailableStock(2L, 1, 10)).thenReturn(10003);

        InitStockRequest request = new InitStockRequest();
        request.setAvailableStock(10003);

        bucketService.initStock(2L, request);

        ArgumentCaptor<TicketStockBucket> captor = ArgumentCaptor.forClass(TicketStockBucket.class);
        verify(ticketStockBucketMapper, org.mockito.Mockito.times(10)).insert(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TicketStockBucket::getAvailableStock)
                .containsExactly(1001, 1001, 1001, 1000, 1000, 1000, 1000, 1000, 1000, 1000);
    }

    @Test
    void initStockRejectsExistingLockedOrSoldStock() {
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(0);
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(stock(1L, 2L, 99, 1, 0));
        InitStockRequest request = new InitStockRequest();
        request.setAvailableStock(100);

        assertThatThrownBy(() -> service.initStock(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有锁定或售出库存");
    }

    @Test
    void adjustStockUsesDeltaAndPreheatsWithInFlightDeductedQuantity() {
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        TicketStock before = stock(1L, 2L, 10, 0, 0);
        TicketStock after = stock(1L, 2L, 15, 0, 0);
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(before, after, after);
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(2, 2, 2);
        when(ticketStockMapper.adjustAvailableStock(2L, 5)).thenReturn(1);
        when(stockCacheService.getAvailableStock(2L)).thenReturn(10, 13);
        when(stockLuaService.repairStockByCasDelta(2L, 10, 3)).thenReturn(RedisStockRepairResult.SUCCESS);
        AdjustStockRequest request = new AdjustStockRequest();
        request.setAdjustQuantity(5);
        request.setReason("追加放票");

        var result = service.adjustStock(2L, request);

        verify(ticketStockMapper).adjustAvailableStock(2L, 5);
        verify(stockLuaService).repairStockByCasDelta(2L, 10, 3);
        verify(stockCacheService).clearSoldout(2L);
        assertThat(result.getExpectedRedisAvailableStock()).isEqualTo(13);
    }

    @Test
    void adjustStockRejectsWhenInFlightWouldMakeRedisAvailableNegative() {
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(stock(1L, 2L, 10, 0, 0));
        when(orderRequestMapper.sumInFlightDeductedQuantity(2L)).thenReturn(8);
        AdjustStockRequest request = new AdjustStockRequest();
        request.setAdjustQuantity(-5);
        request.setReason("扣减库存");

        assertThatThrownBy(() -> service.adjustStock(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("在途预扣量过大");
        verify(ticketStockMapper, never()).adjustAvailableStock(2L, -5);
    }

    @Test
    void updateTicketCategoryRejectsPriceChangeWhenOrderExists() {
        TicketCategory existing = ticketCategory();
        existing.setStatus(TicketCategoryStatusEnum.DRAFT.getCode());
        existing.setPrice(BigDecimal.valueOf(880));
        when(ticketCategoryMapper.selectById(2L)).thenReturn(existing);
        when(showMapper.selectSessionById(1L)).thenReturn(session(ShowStatusEnum.DRAFT.getCode()));
        when(orderMapper.countByTicketCategoryId(2L)).thenReturn(1);
        var request = new com.zewbby.smartticket.domain.dto.AdminUpdateTicketCategoryRequest();
        request.setCategoryName("内场票");
        request.setPrice(BigDecimal.valueOf(980));

        assertThatThrownBy(() -> service.updateTicketCategory(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("票档已有订单");
    }

    @Test
    void updateShowRejectsPublishedMetadata() {
        ShowInfo showInfo = showInfo(ShowStatusEnum.PUBLISHED.getCode());
        when(showMapper.selectShowInfoById(1L)).thenReturn(showInfo);
        AdminUpdateShowRequest request = new AdminUpdateShowRequest();
        request.setTitle("新标题");
        request.setArtist("测试艺人");
        request.setVenueId(1L);
        request.setDescription("更新");

        assertThatThrownBy(() -> service.updateShow(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开售期间演出元数据已冻结");

        verify(showMapper, never()).updateShow(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateSessionRejectsPublishedMetadata() {
        PerformanceSession session = session(ShowStatusEnum.PUBLISHED.getCode());
        when(showMapper.selectSessionById(1L)).thenReturn(session);
        AdminUpdateSessionRequest request = new AdminUpdateSessionRequest();
        request.setStartTime(LocalDateTime.now().plusDays(1));
        request.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));

        assertThatThrownBy(() -> service.updateSession(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开售期间场次元数据已冻结");

        verify(showMapper, never()).updateSession(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateTicketCategoryRejectsPublishedMetadata() {
        when(ticketCategoryMapper.selectById(2L)).thenReturn(ticketCategory());
        var request = new com.zewbby.smartticket.domain.dto.AdminUpdateTicketCategoryRequest();
        request.setCategoryName("内场票");
        request.setPrice(BigDecimal.valueOf(980));

        assertThatThrownBy(() -> service.updateTicketCategory(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开售期间票档元数据已冻结");

        verify(ticketCategoryMapper, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updateShowRejectsWhenAnySessionAlreadyStarted() {
        ShowInfo showInfo = showInfo(ShowStatusEnum.DRAFT.getCode());
        PerformanceSession startedSession = session(ShowStatusEnum.DRAFT.getCode());
        startedSession.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(showMapper.selectShowInfoById(1L)).thenReturn(showInfo);
        when(showMapper.adminSelectSessionsByShowId(1L)).thenReturn(List.of(startedSession));
        AdminUpdateShowRequest request = new AdminUpdateShowRequest();
        request.setTitle("新标题");
        request.setArtist("测试艺人");
        request.setVenueId(1L);
        request.setDescription("更新");

        assertThatThrownBy(() -> service.updateShow(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演出已有场次开演");

        verify(showMapper, never()).updateShow(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishShowRejectsWhenAnySessionAlreadyStarted() {
        PerformanceSession startedSession = session(ShowStatusEnum.DRAFT.getCode());
        startedSession.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(showMapper.selectShowInfoById(1L)).thenReturn(showInfo(ShowStatusEnum.DRAFT.getCode()));
        when(showMapper.adminSelectSessionsByShowId(1L)).thenReturn(List.of(startedSession));

        assertThatThrownBy(() -> service.publishShow(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演出已有场次开演");

        verify(showMapper, never()).updateShowStatus(1L, ShowStatusEnum.PUBLISHED.getCode());
    }

    @Test
    void publishSessionRejectsStartedSession() {
        PerformanceSession startedSession = session(ShowStatusEnum.DRAFT.getCode());
        startedSession.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(showMapper.selectSessionById(1L)).thenReturn(startedSession);
        when(showMapper.selectShowInfoById(1L)).thenReturn(showInfo(ShowStatusEnum.DRAFT.getCode()));

        assertThatThrownBy(() -> service.publishSession(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("场次已开演");

        verify(showMapper, never()).updateSessionStatus(1L, ShowStatusEnum.PUBLISHED.getCode());
    }

    @Test
    void publishTicketCategoryRejectsWhenParentSessionAlreadyStarted() {
        TicketCategory existing = ticketCategory();
        existing.setStatus(TicketCategoryStatusEnum.DRAFT.getCode());
        PerformanceSession startedSession = session(ShowStatusEnum.DRAFT.getCode());
        startedSession.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(ticketCategoryMapper.selectById(2L)).thenReturn(existing);
        when(showMapper.selectSessionById(1L)).thenReturn(startedSession);

        assertThatThrownBy(() -> service.publishTicketCategory(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("票档所属场次已开演");

        verify(ticketCategoryMapper, never()).updateStatus(2L, TicketCategoryStatusEnum.PUBLISHED.getCode());
    }

    @Test
    void updateTicketCategoryRejectsWhenParentSessionAlreadyStarted() {
        TicketCategory existing = ticketCategory();
        existing.setStatus(TicketCategoryStatusEnum.DRAFT.getCode());
        PerformanceSession startedSession = session(ShowStatusEnum.DRAFT.getCode());
        startedSession.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(ticketCategoryMapper.selectById(2L)).thenReturn(existing);
        when(showMapper.selectSessionById(1L)).thenReturn(startedSession);
        var request = new com.zewbby.smartticket.domain.dto.AdminUpdateTicketCategoryRequest();
        request.setCategoryName("内场票");
        request.setPrice(BigDecimal.valueOf(980));

        assertThatThrownBy(() -> service.updateTicketCategory(2L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("票档所属场次已开演");

        verify(ticketCategoryMapper, never()).update(org.mockito.ArgumentMatchers.any());
    }

    private TicketCategory ticketCategory() {
        TicketCategory ticketCategory = new TicketCategory();
        ticketCategory.setId(2L);
        ticketCategory.setSessionId(1L);
        ticketCategory.setCategoryName("内场票");
        ticketCategory.setPrice(BigDecimal.valueOf(880));
        ticketCategory.setStatus(TicketCategoryStatusEnum.PUBLISHED.getCode());
        return ticketCategory;
    }

    private TicketStock stock(Long id, Long ticketCategoryId, Integer available, Integer locked, Integer sold) {
        TicketStock stock = new TicketStock();
        stock.setId(id);
        stock.setTicketCategoryId(ticketCategoryId);
        stock.setTotalStock(available + locked + sold);
        stock.setAvailableStock(available);
        stock.setLockedStock(locked);
        stock.setSoldStock(sold);
        stock.setVersion(0);
        return stock;
    }

    private AdminBusinessServiceImpl bucketEnabledService() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(true);
        properties.setDefaultBucketCount(10);
        return new AdminBusinessServiceImpl(
                venueMapper,
                showMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                ticketStockBucketMapper,
                orderMapper,
                orderRequestMapper,
                stockCacheService,
                stockLuaService,
                properties,
                null
        );
    }

    private ShowInfo showInfo(String status) {
        ShowInfo showInfo = new ShowInfo();
        showInfo.setId(1L);
        showInfo.setVenueId(1L);
        showInfo.setTitle("测试演出");
        showInfo.setArtist("测试艺人");
        showInfo.setStatus(status);
        return showInfo;
    }

    private PerformanceSession session(String status) {
        PerformanceSession session = new PerformanceSession();
        session.setId(1L);
        session.setShowId(1L);
        session.setStartTime(LocalDateTime.now().plusDays(1));
        session.setEndTime(LocalDateTime.now().plusDays(1).plusHours(2));
        session.setStatus(status);
        return session;
    }

    private java.util.List<TicketStockBucket> bucketList(Long ticketCategoryId, int... availableStocks) {
        java.util.List<TicketStockBucket> buckets = new java.util.ArrayList<>();
        for (int bucketNo = 0; bucketNo < availableStocks.length; bucketNo++) {
            TicketStockBucket bucket = new TicketStockBucket();
            bucket.setTicketCategoryId(ticketCategoryId);
            bucket.setBucketVersion(1);
            bucket.setBucketNo(bucketNo);
            bucket.setTotalStock(availableStocks[bucketNo]);
            bucket.setAvailableStock(availableStocks[bucketNo]);
            bucket.setLockedStock(0);
            bucket.setSoldStock(0);
            buckets.add(bucket);
        }
        return buckets;
    }
}
