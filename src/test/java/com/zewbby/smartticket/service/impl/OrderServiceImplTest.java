package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.cache.OrderSubmitGuard;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.domain.dto.CreateOrderRequest;
import com.zewbby.smartticket.domain.dto.OrderSnapshot;
import com.zewbby.smartticket.domain.dto.RedisStockDeductResponse;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.enums.RedisStockDeductResult;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.idempotency.IdempotencyTokenService;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.PaymentMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.mq.OrderTimeoutProducer;
import com.zewbby.smartticket.ratelimit.RateLimitService;
import com.zewbby.smartticket.service.AsyncOrderInFlightService;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import com.zewbby.smartticket.service.AsyncOrderRequestResultCacheService;
import com.zewbby.smartticket.service.ActivityDegradeService;
import com.zewbby.smartticket.service.BucketRouteService;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.PaymentAuditService;
import com.zewbby.smartticket.service.RiskControlService;
import com.zewbby.smartticket.service.StockCacheService;
import com.zewbby.smartticket.service.StockLuaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderRequestMapper orderRequestMapper;

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TicketCategoryMapper ticketCategoryMapper;

    @Mock
    private TicketStockMapper ticketStockMapper;

    @Mock
    private TicketStockBucketMapper ticketStockBucketMapper;

    @Mock
    private OrderSubmitGuard orderSubmitGuard;

    @Mock
    private OrderTimeoutProducer orderTimeoutProducer;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private IdempotencyTokenService idempotencyTokenService;

    @Mock
    private StockLuaService stockLuaService;

    @Mock
    private StockCacheService stockCacheService;

    @Mock
    private AsyncOrderMessagePublisher asyncOrderMessagePublisher;

    @Mock
    private PaymentAuditService paymentAuditService;

    @Mock
    private ObservabilityMetricsService observabilityMetricsService;

    @Mock
    private AsyncOrderInFlightService asyncOrderInFlightService;

    @Mock
    private AsyncOrderRequestResultCacheService asyncOrderRequestResultCacheService;

    @Mock
    private RiskControlService riskControlService;

    @Mock
    private ActivityDegradeService activityDegradeService;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(1L);
        orderService = new OrderServiceImpl(
                orderMapper,
                orderRequestMapper,
                paymentMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                orderSubmitGuard,
                orderTimeoutProducer,
                rateLimitService,
                idempotencyTokenService,
                stockLuaService,
                stockCacheService,
                asyncOrderMessagePublisher,
                paymentAuditService,
                observabilityMetricsService
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createOrderWithValidRelationDeductsStockAndUsesUnifiedTimeout() {
        mockCreateOrderHappyPath();

        var response = orderService.createOrder(validRequest());

        assertThat(response.getStatus()).isEqualTo(OrderStatusEnum.PENDING_PAYMENT.getCode());
        assertThat(Duration.between(response.getCreatedAt(), response.getExpireTime()).toMinutes())
                .isEqualTo(OrderConstant.ORDER_TIMEOUT_MINUTES);
        verify(ticketStockMapper).decreaseStock(2L, 1);
        verify(orderMapper).insert(any(TicketOrder.class));
        verify(orderTimeoutProducer).sendOrderTimeoutMessage(any(OrderTimeoutMessage.class));
    }

    @Test
    void createOrderSavesOrderSnapshot() {
        mockCreateOrderHappyPath();

        orderService.createOrder(validRequest());

        ArgumentCaptor<TicketOrder> orderCaptor = ArgumentCaptor.forClass(TicketOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        TicketOrder savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.getShowTitle()).isEqualTo("测试演唱会");
        assertThat(savedOrder.getTicketCategoryName()).isEqualTo("内场票");
        assertThat(savedOrder.getTicketPrice()).isEqualByComparingTo("880.00");
        assertThat(savedOrder.getTotalAmount()).isEqualByComparingTo("880.00");
    }

    @Test
    void createOrderUsesCurrentUserIdAndIgnoresRequestUserId() {
        mockCreateOrderHappyPath();
        CreateOrderRequest request = new CreateOrderRequest(999L, 1L, 1L, 2L, 1, "idem_test");

        orderService.createOrder(request);

        ArgumentCaptor<TicketOrder> orderCaptor = ArgumentCaptor.forClass(TicketOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getUserId()).isEqualTo(1L);
        verify(userMapper).selectById(1L);
        verify(idempotencyTokenService).consumeOrderToken(1L, "idem_test");
    }

    @Test
    void createOrderFailsWhenStockIsNotEnoughAndDoesNotCreateOrder() {
        mockCommonCreateOrderChecks(true);
        when(ticketCategoryMapper.selectOrderSnapshot(1L, 1L, 2L)).thenReturn(orderSnapshot());
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(ticketStock(0));
        when(ticketStockMapper.decreaseStock(2L, 1)).thenReturn(0);

        assertThatThrownBy(() -> orderService.createOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.STOCK_NOT_ENOUGH);

        verify(orderMapper, never()).insert(any(TicketOrder.class));
    }

    @ParameterizedTest
    @CsvSource({
            "999, 1, 2",
            "1, 999, 2",
            "1, 1, 999",
            "999, 999, 2",
            "1, 999, 999"
    })
    void createOrderRejectsInvalidShowSessionTicketCategoryRelation(Long showId,
                                                                    Long sessionId,
                                                                    Long ticketCategoryId) {
        CreateOrderRequest request = new CreateOrderRequest(null, showId, sessionId, ticketCategoryId, 1, "idem_test");
        mockCommonCreateOrderChecks(false);

        assertThatThrownBy(() -> orderService.createOrder(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);

        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(ticketStockMapper, never()).decreaseStock(anyLong(), anyInt());
        verify(orderMapper, never()).insert(any(TicketOrder.class));
    }

    @Test
    void submitAsyncOrderRejectsInvalidRelationBeforeRequestIsSaved() {
        mockCommonCreateOrderChecks(false);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);

        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(orderRequestMapper, never()).insert(any());
        verify(asyncOrderMessagePublisher, never()).publish(any());
    }

    @Test
    void submitAsyncOrderUsesCurrentUserIdAndIgnoresRequestUserId() {
        mockCommonCreateOrderChecks(true);
        when(orderRequestMapper.insert(any())).thenAnswer(invocation -> {
            TicketOrderRequest orderRequest = invocation.getArgument(0);
            assertThat(orderRequest.getStatus()).isEqualTo(OrderRequestStatusEnum.QUEUED.getCode());
            assertThat(orderRequest.getMessageId()).startsWith("MSGREQ");
            assertThat(orderRequest.getRedisDeducted()).isTrue();
            assertThat(orderRequest.getDeductedQuantity()).isEqualTo(1);
            orderRequest.setId(10L);
            return 1;
        });
        when(stockLuaService.preDeductStock(anyString(), anyLong(), anyInt()))
                .thenReturn(RedisStockDeductResult.SUCCESS);
        when(asyncOrderMessagePublisher.publish(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreateOrderRequest request = new CreateOrderRequest(999L, 1L, 1L, 2L, 1, "idem_test");

        var response = orderService.submitAsyncOrder(request);

        ArgumentCaptor<TicketOrderRequest> requestCaptor = ArgumentCaptor.forClass(TicketOrderRequest.class);
        ArgumentCaptor<AsyncCreateOrderMessage> messageCaptor = ArgumentCaptor.forClass(AsyncCreateOrderMessage.class);
        verify(orderRequestMapper).insert(requestCaptor.capture());
        verify(asyncOrderMessagePublisher).publish(eq(requestCaptor.getValue().getMessageId()), messageCaptor.capture());
        assertThat(requestCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(requestCaptor.getValue().getStatus()).isEqualTo(OrderRequestStatusEnum.QUEUED.getCode());
        assertThat(requestCaptor.getValue().getRedisDeducted()).isTrue();
        assertThat(requestCaptor.getValue().getDeductedQuantity()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(OrderRequestStatusEnum.QUEUED.getCode());
        assertThat(response.getRedisDeducted()).isTrue();
        assertThat(response.getDeductedQuantity()).isEqualTo(1);
        assertThat(messageCaptor.getValue().getUserId()).isEqualTo(1L);
        assertThat(messageCaptor.getValue().getActivityScopeKey()).isEqualTo("show:1:session:1");
        assertThat(messageCaptor.getValue().getRoutingPartitionKey()).isEqualTo("show:1:session:1:ticket:2");
        verify(idempotencyTokenService).consumeOrderToken(1L, "idem_test");
        verify(stockLuaService).preDeductStock(anyString(), anyLong(), anyInt());
        verify(orderRequestMapper, never()).markQueued(anyLong(), anyString());
        verify(userMapper, never()).selectById(999L);
    }

    @Test
    void submitAsyncOrderRejectsWhenActivityRateLimitIsExceeded() {
        mockCommonCreateOrderChecks(true);
        when(rateLimitService.tryAcquireOrderActivityAndTicket("show:1:session:1", 2L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.RATE_LIMITED);

        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(stockLuaService, never()).preDeductStock(anyString(), anyLong(), anyInt());
        verify(asyncOrderMessagePublisher, never()).publish(anyString(), any());
    }

    @Test
    void submitAsyncOrderRejectsBeforeTokenAndStockWhenRiskControlBlocksRequest() {
        ReflectionTestUtils.setField(orderService, "riskControlService", riskControlService);
        when(rateLimitService.tryAcquireOrderSubmit(anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(true);
        when(riskControlService.allowOrderSubmit(1L, "10.0.0.1", null)).thenReturn(false);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest(), "10.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.RATE_LIMITED);

        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(stockLuaService, never()).preDeductStock(anyString(), anyLong(), anyInt());
        verify(asyncOrderMessagePublisher, never()).publish(anyString(), any());
    }

    @Test
    void submitAsyncOrderRejectsBeforeRelationAndTokenWhenActivityIsClosed() {
        ReflectionTestUtils.setField(orderService, "activityDegradeService", activityDegradeService);
        when(rateLimitService.tryAcquireOrderSubmit(anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(true);
        when(stockCacheService.isSoldOut(2L)).thenReturn(false);
        when(orderSubmitGuard.tryAcquire(1L, 2L)).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(new UserAccount(1L, "tester", "13800000001", "encoded", "NORMAL", "USER", null, null));
        when(activityDegradeService.isOrderSubmitClosed("show:1:session:1")).thenReturn(true);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_QUEUE_BUSY);

        verify(ticketCategoryMapper, never()).existsShowSessionTicketCategoryRelation(anyLong(), anyLong(), anyLong());
        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(stockLuaService, never()).preDeductStock(anyString(), anyLong(), anyInt());
        verify(asyncOrderMessagePublisher, never()).publish(anyString(), any());
    }

    @Test
    void submitAsyncOrderCanSkipRequestInsertWhenFastPipelineIsEnabled() {
        orderService = fastSubmitOrderService();
        ReflectionTestUtils.setField(orderService, "asyncOrderRequestResultCacheService", asyncOrderRequestResultCacheService);
        mockCommonCreateOrderChecks(true);
        when(stockLuaService.preDeductStock(anyString(), anyLong(), anyInt()))
                .thenReturn(RedisStockDeductResult.SUCCESS);
        when(asyncOrderMessagePublisher.publish(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = orderService.submitAsyncOrder(validRequest());

        ArgumentCaptor<AsyncCreateOrderMessage> messageCaptor = ArgumentCaptor.forClass(AsyncCreateOrderMessage.class);
        verify(orderRequestMapper, never()).insert(any());
        verify(asyncOrderMessagePublisher).publish(anyString(), messageCaptor.capture());
        assertThat(response.getStatus()).isEqualTo(OrderRequestStatusEnum.QUEUED.getCode());
        assertThat(response.getRedisDeducted()).isTrue();
        assertThat(messageCaptor.getValue().getRequestId()).isEqualTo(response.getRequestId());
        assertThat(messageCaptor.getValue().getRedisDeducted()).isTrue();
        assertThat(messageCaptor.getValue().getDeductedQuantity()).isEqualTo(1);
        assertThat(messageCaptor.getValue().getDeductedAt()).isNotNull();
        assertThat(messageCaptor.getValue().getMessageId()).startsWith("MSGREQ");
        verify(asyncOrderRequestResultCacheService).cacheQueuedResult(eq(1L), any(OrderRequestVO.class));
    }

    @Test
    void getOrderRequestResultReturnsCachedResultBeforeQueryingDatabase() {
        ReflectionTestUtils.setField(orderService, "asyncOrderRequestResultCacheService", asyncOrderRequestResultCacheService);
        OrderRequestVO cached = new OrderRequestVO();
        cached.setRequestId("REQ1");
        cached.setStatus(OrderRequestStatusEnum.SUCCESS.getCode());
        cached.setOrderId(100L);
        when(asyncOrderRequestResultCacheService.getCachedResult(1L, "REQ1")).thenReturn(cached);

        var response = orderService.getOrderRequestResult("REQ1");

        assertThat(response.getOrderId()).isEqualTo(100L);
        verify(orderRequestMapper, never()).selectByRequestIdAndUserId(anyString(), anyLong());
    }

    @Test
    void submitAsyncOrderRecordsActualStockBucketNoWhenBucketEnabled() {
        orderService = bucketEnabledOrderService();
        mockCommonCreateOrderChecks(true);
        when(orderRequestMapper.insert(any())).thenAnswer(invocation -> {
            TicketOrderRequest orderRequest = invocation.getArgument(0);
            assertThat(orderRequest.getStatus()).isEqualTo(OrderRequestStatusEnum.QUEUED.getCode());
            assertThat(orderRequest.getMessageId()).startsWith("MSGREQ");
            assertThat(orderRequest.getRedisDeducted()).isTrue();
            assertThat(orderRequest.getDeductedQuantity()).isEqualTo(1);
            assertThat(orderRequest.getStockBucketNo()).isEqualTo(4);
            orderRequest.setId(10L);
            return 1;
        });
        when(stockLuaService.preDeductBucketStock(anyString(), eq(2L), eq(1), eq(1), anyInt(), eq(10), eq(3)))
                .thenReturn(new RedisStockDeductResponse(RedisStockDeductResult.SUCCESS, 4));
        when(asyncOrderMessagePublisher.publish(anyString(), any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = orderService.submitAsyncOrder(validRequest());

        ArgumentCaptor<TicketOrderRequest> requestCaptor = ArgumentCaptor.forClass(TicketOrderRequest.class);
        verify(orderRequestMapper).insert(requestCaptor.capture());
        assertThat(response.getStockBucketNo()).isEqualTo(4);
        assertThat(requestCaptor.getValue().getStockBucketNo()).isEqualTo(4);
        verify(stockLuaService).preDeductBucketStock(anyString(), eq(2L), eq(1), eq(1), anyInt(), eq(10), eq(3));
        verify(stockLuaService, never()).preDeductStock(anyString(), anyLong(), anyInt());
    }

    @Test
    void submitAsyncOrderReleasesOriginalBucketWhenLocalMessageCreateFailsAfterPreDeduct() {
        orderService = bucketEnabledOrderService();
        mockCommonCreateOrderChecks(true);
        when(orderRequestMapper.insert(any())).thenAnswer(invocation -> {
            TicketOrderRequest orderRequest = invocation.getArgument(0);
            orderRequest.setId(10L);
            return 1;
        });
        when(stockLuaService.preDeductBucketStock(anyString(), eq(2L), eq(1), eq(1), anyInt(), eq(10), eq(3)))
                .thenReturn(new RedisStockDeductResponse(RedisStockDeductResult.SUCCESS, 4));
        when(asyncOrderMessagePublisher.publish(anyString(), any()))
                .thenThrow(new BusinessException("本地消息创建失败"));
        when(stockLuaService.releasePreDeductedStock(anyString(), eq(2L), eq(1), eq(4), eq(1)))
                .thenReturn(RedisStockReleaseResult.SUCCESS);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本地消息创建失败");

        verify(orderRequestMapper).markFailed(10L, "异步下单提交失败");
        verify(stockLuaService).releasePreDeductedStock(anyString(), eq(2L), eq(1), eq(4), eq(1));
        verify(orderRequestMapper).markCompensated(eq(10L), any());
    }

    @Test
    void submitAsyncOrderDoesNotCreateRequestOrPublishWhenRedisStockIsNotEnough() {
        mockCommonCreateOrderChecks(true);
        when(stockLuaService.preDeductStock(anyString(), anyLong(), anyInt()))
                .thenReturn(RedisStockDeductResult.STOCK_NOT_ENOUGH);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.STOCK_NOT_ENOUGH);

        verify(orderRequestMapper, never()).insert(any());
        verify(orderRequestMapper, never()).markFailed(anyLong(), anyString());
        verify(asyncOrderMessagePublisher, never()).publish(any());
    }

    @Test
    void submitAsyncOrderFastFailsWhenSoldoutAndDoesNotCreateRequestOrLocalMessage() {
        when(rateLimitService.tryAcquireOrderSubmit(anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(true);
        when(stockCacheService.isSoldOut(2L)).thenReturn(true);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.TICKET_SOLD_OUT);

        verify(orderRequestMapper, never()).insert(any());
        verify(asyncOrderMessagePublisher, never()).publish(any());
        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(stockLuaService, never()).preDeductStock(anyString(), anyLong(), anyInt());
    }

    @Test
    void submitAsyncOrderRejectsWhenInFlightQueueIsFullBeforeConsumingToken() {
        orderService = orderServiceWithInFlightControl();
        mockCommonCreateOrderChecks(true);
        when(asyncOrderInFlightService.tryAcquire(2L)).thenReturn(false);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_QUEUE_BUSY);

        verify(idempotencyTokenService, never()).consumeOrderToken(anyLong(), anyString());
        verify(stockLuaService, never()).preDeductStock(anyString(), anyLong(), anyInt());
        verify(asyncOrderMessagePublisher, never()).publish(anyString(), any());
    }

    @Test
    void submitAsyncOrderReleasesInFlightWhenRedisPreDeductFails() {
        orderService = orderServiceWithInFlightControl();
        mockCommonCreateOrderChecks(true);
        when(asyncOrderInFlightService.tryAcquire(2L)).thenReturn(true);
        when(stockLuaService.preDeductStock(anyString(), anyLong(), anyInt()))
                .thenReturn(RedisStockDeductResult.STOCK_NOT_ENOUGH);

        assertThatThrownBy(() -> orderService.submitAsyncOrder(validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.STOCK_NOT_ENOUGH);

        verify(asyncOrderInFlightService).release(2L);
        verify(asyncOrderMessagePublisher, never()).publish(anyString(), any());
    }

    @Test
    void oldDirectPayEndpointCannotBypassPaymentOrder() {
        assertThatThrownBy(() -> orderService.payOrder(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_REQUIRED);

        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), anyInt());
    }

    @Test
    void pendingPaymentCanBecomeCancelledAndReleaseStock() {
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        TicketOrder cancelled = order(1L, OrderStatusEnum.CANCELLED.getCode());
        when(orderMapper.selectByIdAndUserId(1L, 1L)).thenReturn(pending, cancelled);
        when(orderMapper.updateCancelStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        when(ticketStockMapper.rollbackStock(2L, 1)).thenReturn(1);

        var response = orderService.cancelOrder(1L);

        assertThat(response.getStatus()).isEqualTo(OrderStatusEnum.CANCELLED.getCode());
        verify(ticketStockMapper).rollbackStock(2L, 1);
        verify(paymentMapper).closeUnpaidByOrderId(anyLong(), any());
    }

    @Test
    void cancellingAsyncBucketOrderRollsBackVersionedBucketAndReleasesOriginalRedisDeduction() {
        orderService = bucketEnabledOrderService();
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        TicketOrder cancelled = order(1L, OrderStatusEnum.CANCELLED.getCode());
        when(orderMapper.selectByIdAndUserId(1L, 1L)).thenReturn(pending, cancelled);
        when(orderMapper.updateCancelStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        TicketOrderRequest asyncRequest = asyncBucketOrderRequest(10L, "REQ1", 2, 4);
        when(orderRequestMapper.selectByOrderId(1L)).thenReturn(asyncRequest);
        when(ticketStockBucketMapper.rollbackStockByVersion(2L, 2, 4, 1)).thenReturn(1);
        when(stockLuaService.releasePreDeductedStock("REQ1", 2L, 2, 4, 1))
                .thenReturn(RedisStockReleaseResult.SUCCESS);

        var response = orderService.cancelOrder(1L);

        assertThat(response.getStatus()).isEqualTo(OrderStatusEnum.CANCELLED.getCode());
        verify(ticketStockMapper, never()).rollbackStock(anyLong(), anyInt());
        verify(ticketStockBucketMapper).rollbackStockByVersion(2L, 2, 4, 1);
        verify(stockLuaService).releasePreDeductedStock("REQ1", 2L, 2, 4, 1);
    }

    @Test
    void cancellingAsyncBucketOrderRejectsGhostRedisRefundWhenDeductRecordIsMissing() {
        orderService = bucketEnabledOrderService();
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(orderMapper.selectByIdAndUserId(1L, 1L)).thenReturn(pending);
        when(orderMapper.updateCancelStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        TicketOrderRequest asyncRequest = asyncBucketOrderRequest(10L, "REQ1", 2, 4);
        when(orderRequestMapper.selectByOrderId(1L)).thenReturn(asyncRequest);
        when(ticketStockBucketMapper.rollbackStockByVersion(2L, 2, 4, 1)).thenReturn(1);
        when(stockLuaService.releasePreDeductedStock("REQ1", 2L, 2, 4, 1))
                .thenReturn(RedisStockReleaseResult.NOT_DEDUCTED);

        assertThatThrownBy(() -> orderService.cancelOrder(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis预扣库存释放失败");

        verify(stockLuaService).releasePreDeductedStock("REQ1", 2L, 2, 4, 1);
    }

    @Test
    void repeatedTimeoutCloseDoesNotReleaseStockTwice() {
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(orderMapper.selectById(1L)).thenReturn(pending, order(1L, OrderStatusEnum.CLOSED.getCode()));
        when(orderMapper.updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        when(ticketStockMapper.rollbackStock(2L, 1)).thenReturn(1);

        orderService.closeTimeoutOrder(1L);
        orderService.closeTimeoutOrder(1L);

        verify(orderMapper).updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString());
        verify(ticketStockMapper).rollbackStock(2L, 1);
        verify(paymentMapper).closeUnpaidByOrderId(anyLong(), any());
    }

    @Test
    void pendingPaymentCanBeClosedAndReleaseStock() {
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(orderMapper.selectById(1L)).thenReturn(pending);
        when(orderMapper.updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        when(ticketStockMapper.rollbackStock(2L, 1)).thenReturn(1);

        orderService.closeTimeoutOrder(1L);

        verify(orderMapper).updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString());
        verify(ticketStockMapper).rollbackStock(2L, 1);
        verify(paymentMapper).closeUnpaidByOrderId(anyLong(), any());
    }

    @Test
    void timeoutCloseAsyncBucketOrderRollsBackVersionedBucketAndReleasesOriginalRedisDeduction() {
        orderService = bucketEnabledOrderService();
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(orderMapper.selectById(1L)).thenReturn(pending);
        when(orderMapper.updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        TicketOrderRequest asyncRequest = asyncBucketOrderRequest(10L, "REQ1", 2, 4);
        when(orderRequestMapper.selectByOrderId(1L)).thenReturn(asyncRequest);
        when(ticketStockBucketMapper.rollbackStockByVersion(2L, 2, 4, 1)).thenReturn(1);
        when(stockLuaService.releasePreDeductedStock("REQ1", 2L, 2, 4, 1))
                .thenReturn(RedisStockReleaseResult.SUCCESS);

        orderService.closeTimeoutOrder(1L);

        verify(ticketStockMapper, never()).rollbackStock(anyLong(), anyInt());
        verify(ticketStockBucketMapper).rollbackStockByVersion(2L, 2, 4, 1);
        verify(stockLuaService).releasePreDeductedStock("REQ1", 2L, 2, 4, 1);
    }

    @Test
    void timeoutCloseDoesNotDependOnUserContext() {
        UserContext.clear();
        TicketOrder pending = order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(orderMapper.selectById(1L)).thenReturn(pending);
        when(orderMapper.updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString())).thenReturn(1);
        when(ticketStockMapper.rollbackStock(2L, 1)).thenReturn(1);

        orderService.closeTimeoutOrder(1L);

        verify(orderMapper).updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString());
        verify(ticketStockMapper).rollbackStock(2L, 1);
        verify(paymentMapper).closeUnpaidByOrderId(anyLong(), any());
    }

    @Test
    void paidOrderCannotBeCancelledOrReleasedByCancelLogic() {
        when(orderMapper.selectByIdAndUserId(1L, 1L)).thenReturn(order(1L, OrderStatusEnum.PAID.getCode()));

        assertThatThrownBy(() -> orderService.cancelOrder(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);

        verify(orderMapper, never()).updateCancelStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any(), anyString());
        verify(ticketStockMapper, never()).rollbackStock(anyLong(), anyInt());
    }

    @Test
    void closedOrderCannotBePaidThroughOldDirectPayEndpoint() {
        assertThatThrownBy(() -> orderService.payOrder(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_REQUIRED);

        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), anyInt());
    }

    @Test
    void cancelledOrderCannotBePaidThroughOldDirectPayEndpoint() {
        assertThatThrownBy(() -> orderService.payOrder(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_REQUIRED);

        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), anyInt());
    }

    @Test
    void currentUserCannotQueryAnotherUsersOrder() {
        when(orderMapper.selectByIdAndUserId(99L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> orderService.getOrderById(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_NOT_FOUND);
    }

    @Test
    void currentUserCanListOnlyCurrentUserOrders() {
        when(orderMapper.selectByUserId(1L)).thenReturn(List.of(order(1L, OrderStatusEnum.PENDING_PAYMENT.getCode())));

        var orders = orderService.listCurrentUserOrders();

        assertThat(orders).hasSize(1);
        verify(orderMapper).selectByUserId(1L);
    }

    @Test
    void currentUserCanQueryOwnAsyncOrderRequest() {
        TicketOrderRequest request = orderRequest("REQ_1", 1L);
        when(orderRequestMapper.selectByRequestIdAndUserId("REQ_1", 1L)).thenReturn(request);

        var response = orderService.getOrderRequestResult("REQ_1");

        assertThat(response.getRequestId()).isEqualTo("REQ_1");
        verify(orderRequestMapper).selectByRequestIdAndUserId("REQ_1", 1L);
    }

    @Test
    void currentUserCannotQueryAnotherUsersAsyncOrderRequest() {
        when(orderRequestMapper.selectByRequestIdAndUserId("REQ_2", 1L)).thenReturn(null);

        assertThatThrownBy(() -> orderService.getOrderRequestResult("REQ_2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_REQUEST_NOT_FOUND);
    }

    @Test
    void currentUserCanQueryFastPipelineQueuedResultBeforeConsumerCreatesRequest() {
        ReflectionTestUtils.setField(orderService, "asyncOrderRequestResultCacheService", asyncOrderRequestResultCacheService);
        OrderRequestVO cached = new OrderRequestVO();
        cached.setRequestId("REQ_FAST");
        cached.setStatus(OrderRequestStatusEnum.QUEUED.getCode());
        when(asyncOrderRequestResultCacheService.getCachedResult(1L, "REQ_FAST")).thenReturn(cached);

        var response = orderService.getOrderRequestResult("REQ_FAST");

        assertThat(response.getRequestId()).isEqualTo("REQ_FAST");
        assertThat(response.getStatus()).isEqualTo(OrderRequestStatusEnum.QUEUED.getCode());
        verify(orderRequestMapper, never()).selectByRequestIdAndUserId(anyString(), anyLong());
    }

    @Test
    void currentUserCannotCancelAnotherUsersOrder() {
        when(orderMapper.selectByIdAndUserId(99L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> orderService.cancelOrder(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_NOT_FOUND);

        verify(orderMapper, never()).updateCancelStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void oldDirectPayEndpointCannotPayAnotherUsersOrder() {
        assertThatThrownBy(() -> orderService.payOrder(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_REQUIRED);

        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void paidOrAlreadyClosedOrdersAreNotClosedAgainByTimeoutTask() {
        when(orderMapper.selectById(1L)).thenReturn(order(1L, OrderStatusEnum.PAID.getCode()));
        when(orderMapper.selectById(2L)).thenReturn(order(2L, OrderStatusEnum.CLOSED.getCode()));

        orderService.closeTimeoutOrder(1L);
        orderService.closeTimeoutOrder(2L);

        verify(orderMapper, never()).updateCloseStatus(anyLong(), anyString(), anyString(), any(), anyString());
        verify(ticketStockMapper, never()).rollbackStock(anyLong(), anyInt());
    }

    private void mockCreateOrderHappyPath() {
        mockCommonCreateOrderChecks(true);
        when(ticketCategoryMapper.selectOrderSnapshot(1L, 1L, 2L)).thenReturn(orderSnapshot());
        when(ticketStockMapper.selectByTicketCategoryId(2L)).thenReturn(ticketStock(10));
        when(ticketStockMapper.decreaseStock(2L, 1)).thenReturn(1);
        when(orderMapper.insert(any(TicketOrder.class))).thenAnswer(invocation -> {
            TicketOrder order = invocation.getArgument(0);
            order.setId(100L);
            return 1;
        });
    }

    private void mockCommonCreateOrderChecks(boolean relationExists) {
        when(rateLimitService.tryAcquireOrderSubmit(anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(true);
        lenient().when(rateLimitService.tryAcquireOrderActivity(anyString())).thenReturn(true);
        lenient().when(rateLimitService.tryAcquireOrderTicket(anyLong())).thenReturn(true);
        lenient().when(rateLimitService.tryAcquireOrderActivityAndTicket(anyString(), anyLong())).thenReturn(true);
        lenient().when(stockCacheService.isSoldOut(anyLong())).thenReturn(false);
        lenient().when(stockCacheService.isSoldOut(anyLong(), anyInt())).thenReturn(false);
        when(orderSubmitGuard.tryAcquire(anyLong(), anyLong())).thenReturn(true);
        when(userMapper.selectById(1L)).thenReturn(new UserAccount(1L, "tester", "13800000001", "encoded", "NORMAL", "USER", null, null));
        when(ticketCategoryMapper.existsShowSessionTicketCategoryRelation(anyLong(), anyLong(), anyLong()))
                .thenReturn(relationExists);
    }

    private CreateOrderRequest validRequest() {
        return new CreateOrderRequest(null, 1L, 1L, 2L, 1, "idem_test");
    }

    private OrderSnapshot orderSnapshot() {
        OrderSnapshot snapshot = new OrderSnapshot();
        snapshot.setShowTitle("测试演唱会");
        snapshot.setSessionStartTime(LocalDateTime.of(2026, 6, 20, 19, 30));
        snapshot.setTicketCategoryName("内场票");
        snapshot.setTicketPrice(new BigDecimal("880.00"));
        return snapshot;
    }

    private TicketStock ticketStock(Integer availableStock) {
        return new TicketStock(1L, 2L, 10, availableStock, 0, 0, 0, null, null);
    }

    private OrderServiceImpl bucketEnabledOrderService() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(true);
        properties.setDefaultBucketCount(10);
        properties.setActiveProbeCount(3);
        return new OrderServiceImpl(
                orderMapper,
                orderRequestMapper,
                paymentMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                ticketStockBucketMapper,
                orderSubmitGuard,
                orderTimeoutProducer,
                rateLimitService,
                idempotencyTokenService,
                stockLuaService,
                stockCacheService,
                new BucketRouteService(),
                asyncOrderMessagePublisher,
                paymentAuditService,
                observabilityMetricsService,
                properties,
                null,
                null
        );
    }

    private OrderServiceImpl fastSubmitOrderService() {
        AsyncOrderSubmitProperties asyncOrderSubmitProperties = new AsyncOrderSubmitProperties();
        asyncOrderSubmitProperties.setPersistRequestBeforePublish(false);
        return new OrderServiceImpl(
                orderMapper,
                orderRequestMapper,
                paymentMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                null,
                orderSubmitGuard,
                orderTimeoutProducer,
                rateLimitService,
                idempotencyTokenService,
                stockLuaService,
                stockCacheService,
                new BucketRouteService(),
                asyncOrderMessagePublisher,
                paymentAuditService,
                observabilityMetricsService,
                disabledBucketPropertiesForTest(),
                null,
                null,
                asyncOrderSubmitProperties
        );
    }

    private OrderServiceImpl orderServiceWithInFlightControl() {
        return new OrderServiceImpl(
                orderMapper,
                orderRequestMapper,
                paymentMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                null,
                orderSubmitGuard,
                orderTimeoutProducer,
                rateLimitService,
                idempotencyTokenService,
                stockLuaService,
                stockCacheService,
                new BucketRouteService(),
                asyncOrderMessagePublisher,
                paymentAuditService,
                observabilityMetricsService,
                disabledBucketPropertiesForTest(),
                null,
                null,
                null,
                defaultAsyncSubmitPropertiesForTest(),
                null,
                asyncOrderInFlightService
        );
    }

    private AsyncOrderSubmitProperties defaultAsyncSubmitPropertiesForTest() {
        return new AsyncOrderSubmitProperties();
    }

    private StockBucketProperties disabledBucketPropertiesForTest() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(false);
        return properties;
    }

    private TicketOrder order(Long id, String status) {
        LocalDateTime now = LocalDateTime.now();
        TicketOrder order = new TicketOrder();
        order.setId(id);
        order.setOrderNo("ST" + id);
        order.setUserId(1L);
        order.setShowId(1L);
        order.setSessionId(1L);
        order.setTicketCategoryId(2L);
        order.setQuantity(1);
        order.setTotalAmount(BigDecimal.valueOf(880).setScale(2));
        order.setStatus(status);
        order.setExpireTime(now.plusMinutes(OrderConstant.ORDER_TIMEOUT_MINUTES));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    private TicketOrderRequest orderRequest(String requestId, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        TicketOrderRequest request = new TicketOrderRequest();
        request.setId(1L);
        request.setRequestId(requestId);
        request.setUserId(userId);
        request.setShowId(1L);
        request.setSessionId(1L);
        request.setTicketCategoryId(2L);
        request.setQuantity(1);
        request.setStatus(OrderRequestStatusEnum.PROCESSING.getCode());
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        return request;
    }

    private TicketOrderRequest asyncBucketOrderRequest(Long orderId,
                                                       String requestId,
                                                       Integer bucketVersion,
                                                       Integer bucketNo) {
        TicketOrderRequest request = orderRequest(requestId, 1L);
        request.setId(10L);
        request.setStatus(OrderRequestStatusEnum.SUCCESS.getCode());
        request.setOrderId(orderId);
        request.setStockBucketVersion(bucketVersion);
        request.setStockBucketNo(bucketNo);
        request.setRedisDeducted(true);
        request.setDeductedQuantity(1);
        request.setDeductedAt(LocalDateTime.now());
        return request;
    }
}
