package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.domain.dto.CreatePaymentRequest;
import com.zewbby.smartticket.domain.dto.MockPaymentRequest;
import com.zewbby.smartticket.domain.entity.PaymentCallbackLog;
import com.zewbby.smartticket.domain.entity.PaymentFlowLog;
import com.zewbby.smartticket.domain.entity.PaymentOrder;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.enums.PaymentStatusEnum;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.PaymentMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.PaymentAuditService;
import com.zewbby.smartticket.service.PaymentSignatureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentMapper paymentMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private OrderRequestMapper orderRequestMapper;

    @Mock
    private TicketStockMapper ticketStockMapper;

    @Mock
    private TicketStockBucketMapper ticketStockBucketMapper;

    @Mock
    private PaymentSignatureService paymentSignatureService;

    @Mock
    private PaymentAuditService paymentAuditService;

    @Mock
    private ObservabilityMetricsService observabilityMetricsService;

    private PaymentServiceImpl paymentService;

    @BeforeEach
    void setUp() {
        UserContext.setUserId(1L);
        paymentService = new PaymentServiceImpl(
                paymentMapper,
                orderMapper,
                ticketStockMapper,
                paymentSignatureService,
                paymentAuditService,
                observabilityMetricsService
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createPaymentCreatesInitPaymentWithAmountFromOrder() {
        TicketOrder order = order(10L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(orderMapper.selectByIdAndUserId(10L, 1L)).thenReturn(order);
        when(paymentMapper.selectByOrderIdAndUserId(10L, 1L)).thenReturn(null);
        when(paymentMapper.insert(any(PaymentOrder.class))).thenAnswer(invocation -> {
            PaymentOrder paymentOrder = invocation.getArgument(0);
            paymentOrder.setId(100L);
            return 1;
        });

        var response = paymentService.createPayment(new CreatePaymentRequest(10L, "MOCK"));

        ArgumentCaptor<PaymentOrder> paymentCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentMapper).insert(paymentCaptor.capture());
        PaymentOrder savedPayment = paymentCaptor.getValue();
        assertThat(savedPayment.getAmount()).isEqualByComparingTo(order.getTotalAmount());
        assertThat(savedPayment.getStatus()).isEqualTo(PaymentStatusEnum.INIT.getCode());
        assertThat(savedPayment.getChannel()).isEqualTo("MOCK");
        assertThat(response.getAmount()).isEqualByComparingTo(order.getTotalAmount());
        verify(paymentAuditService).recordFlowLog(any(PaymentFlowLog.class));
    }

    @Test
    void createPaymentUsesHistoricalOrderSnapshotAmountInsteadOfRecalculatingCurrentPrice() {
        TicketOrder order = order(10L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        order.setQuantity(2);
        order.setTicketPrice(new BigDecimal("880.00"));
        order.setTotalAmount(new BigDecimal("1760.00"));
        when(orderMapper.selectByIdAndUserId(10L, 1L)).thenReturn(order);
        when(paymentMapper.selectByOrderIdAndUserId(10L, 1L)).thenReturn(null);
        when(paymentMapper.insert(any(PaymentOrder.class))).thenReturn(1);

        paymentService.createPayment(new CreatePaymentRequest(10L, "MOCK"));

        ArgumentCaptor<PaymentOrder> paymentCaptor = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(paymentMapper).insert(paymentCaptor.capture());
        /*
         * PaymentService 没有重新查询 ticket_category，也不接收前端金额。
         * 即使后台后来把票档价格改成别的值，支付单金额也只能等于 ticket_order.total_amount 这个历史快照。
         */
        assertThat(paymentCaptor.getValue().getAmount()).isEqualByComparingTo("1760.00");
    }

    @Test
    void createPaymentReturnsExistingPaymentForSameOrder() {
        TicketOrder order = order(10L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        PaymentOrder existing = payment("PAY1", 10L, PaymentStatusEnum.INIT.getCode());
        when(orderMapper.selectByIdAndUserId(10L, 1L)).thenReturn(order);
        when(paymentMapper.selectByOrderIdAndUserId(10L, 1L)).thenReturn(existing);

        var response = paymentService.createPayment(new CreatePaymentRequest(10L, "MOCK"));

        assertThat(response.getPaymentNo()).isEqualTo("PAY1");
        verify(paymentMapper, never()).insert(any(PaymentOrder.class));
    }

    @Test
    void createPaymentFailsForAnotherUsersOrder() {
        when(orderMapper.selectByIdAndUserId(99L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.createPayment(new CreatePaymentRequest(99L, "MOCK")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CANCELLED", "CLOSED"})
    void createPaymentFailsForNonPendingOrder(String status) {
        TicketOrder order = order(10L, status);
        when(orderMapper.selectByIdAndUserId(10L, 1L)).thenReturn(order);
        when(paymentMapper.selectByOrderIdAndUserId(10L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.createPayment(new CreatePaymentRequest(10L, "MOCK")))
                .isInstanceOf(BusinessException.class);

        verify(paymentMapper, never()).insert(any(PaymentOrder.class));
    }

    @Test
    void createPaymentFailsForExpiredOrder() {
        TicketOrder order = order(10L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        order.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(orderMapper.selectByIdAndUserId(10L, 1L)).thenReturn(order);
        when(paymentMapper.selectByOrderIdAndUserId(10L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.createPayment(new CreatePaymentRequest(10L, "MOCK")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_EXPIRED);
    }

    @Test
    void getPaymentOnlyReturnsCurrentUsersPayment() {
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L))
                .thenReturn(payment("PAY1", 10L, PaymentStatusEnum.INIT.getCode()));

        var response = paymentService.getPayment("PAY1");

        assertThat(response.getPaymentNo()).isEqualTo("PAY1");
    }

    @Test
    void getPaymentRejectsAnotherUsersPayment() {
        when(paymentMapper.selectByPaymentNoAndUserId("PAY2", 1L)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.getPayment("PAY2"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_NOT_FOUND);
    }

    @Test
    void mockPaySuccessMarksPaymentPaidOrderPaidAndConfirmsStock() {
        PaymentOrder initPayment = payment("PAY1", 10L, PaymentStatusEnum.INIT.getCode());
        PaymentOrder successPayment = payment("PAY1", 10L, PaymentStatusEnum.SUCCESS.getCode());
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L)).thenReturn(initPayment);
        when(paymentMapper.selectByPaymentNo("PAY1")).thenReturn(initPayment, successPayment);
        when(orderMapper.selectById(10L)).thenReturn(order(10L, OrderStatusEnum.PENDING_PAYMENT.getCode()));
        when(paymentMapper.markSuccess(anyString(), any(), any())).thenReturn(1);
        when(orderMapper.updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(ticketStockMapper.confirmStock(2L, 1)).thenReturn(1);

        var response = paymentService.mockPay(signedRequest("PAY1", true));

        assertThat(response.getStatus()).isEqualTo(PaymentStatusEnum.SUCCESS.getCode());
        verify(paymentMapper).markSuccess(anyString(), any(), any());
        verify(orderMapper).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper).confirmStock(2L, 1);
        verify(paymentAuditService).recordCallbackLog(any(PaymentCallbackLog.class));
        verify(paymentAuditService).recordFlowLog(any(PaymentFlowLog.class));
    }

    @Test
    void mockPaySuccessConfirmsVersionedBucketWhenBucketOrderWasCreatedAsync() {
        paymentService = bucketEnabledPaymentService();
        PaymentOrder initPayment = payment("PAY1", 10L, PaymentStatusEnum.INIT.getCode());
        PaymentOrder successPayment = payment("PAY1", 10L, PaymentStatusEnum.SUCCESS.getCode());
        TicketOrder pendingOrder = order(10L, OrderStatusEnum.PENDING_PAYMENT.getCode());
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L)).thenReturn(initPayment);
        when(paymentMapper.selectByPaymentNo("PAY1")).thenReturn(initPayment, successPayment);
        when(orderMapper.selectById(10L)).thenReturn(pendingOrder);
        when(paymentMapper.markSuccess(anyString(), any(), any())).thenReturn(1);
        when(orderMapper.updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
        TicketOrderRequest orderRequest = bucketOrderRequest(10L, 2, 4);
        when(orderRequestMapper.selectByOrderId(10L)).thenReturn(orderRequest);
        when(ticketStockBucketMapper.confirmStockByVersion(2L, 2, 4, 1)).thenReturn(1);

        var response = paymentService.mockPay(signedRequest("PAY1", true));

        assertThat(response.getStatus()).isEqualTo(PaymentStatusEnum.SUCCESS.getCode());
        verify(ticketStockBucketMapper).confirmStockByVersion(2L, 2, 4, 1);
        verify(ticketStockMapper, never()).confirmStock(anyLong(), any());
    }

    @Test
    void repeatedSuccessCallbackDoesNotConfirmStockAgain() {
        PaymentOrder successPayment = payment("PAY1", 10L, PaymentStatusEnum.SUCCESS.getCode());
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L)).thenReturn(successPayment);
        when(paymentMapper.selectByPaymentNo("PAY1")).thenReturn(successPayment);

        var response = paymentService.mockPay(signedRequest("PAY1", true));

        assertThat(response.getStatus()).isEqualTo(PaymentStatusEnum.SUCCESS.getCode());
        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), any());
        verify(paymentAuditService).recordFlowLog(any(PaymentFlowLog.class));
    }

    @Test
    void mockPayFailureOnlyMarksPaymentFailed() {
        PaymentOrder initPayment = payment("PAY1", 10L, PaymentStatusEnum.INIT.getCode());
        PaymentOrder failedPayment = payment("PAY1", 10L, PaymentStatusEnum.FAILED.getCode());
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L)).thenReturn(initPayment);
        when(paymentMapper.selectByPaymentNo("PAY1")).thenReturn(initPayment, failedPayment);
        when(paymentMapper.markFailed(anyString(), any(), anyString())).thenReturn(1);

        var response = paymentService.mockPay(signedRequest("PAY1", false));

        assertThat(response.getStatus()).isEqualTo(PaymentStatusEnum.FAILED.getCode());
        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), any());
        verify(paymentAuditService).recordFlowLog(any(PaymentFlowLog.class));
    }

    @Test
    void failedPaymentCannotBecomeSuccess() {
        PaymentOrder failedPayment = payment("PAY1", 10L, PaymentStatusEnum.FAILED.getCode());
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L)).thenReturn(failedPayment);
        when(paymentMapper.selectByPaymentNo("PAY1")).thenReturn(failedPayment);

        assertThatThrownBy(() -> paymentService.mockPay(signedRequest("PAY1", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_STATUS_NOT_ALLOWED);

        verify(orderMapper, never()).updatePayStatusByUserId(anyLong(), anyLong(), anyString(), anyString(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "CLOSED"})
    void cancelledOrClosedOrderCannotBePaidByCallback(String orderStatus) {
        PaymentOrder initPayment = payment("PAY1", 10L, PaymentStatusEnum.INIT.getCode());
        when(paymentMapper.selectByPaymentNoAndUserId("PAY1", 1L)).thenReturn(initPayment);
        when(paymentMapper.selectByPaymentNo("PAY1")).thenReturn(initPayment);
        when(orderMapper.selectById(10L)).thenReturn(order(10L, orderStatus));

        assertThatThrownBy(() -> paymentService.mockPay(signedRequest("PAY1", true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);

        verify(paymentMapper, never()).markSuccess(anyString(), any(), any());
        verify(ticketStockMapper, never()).confirmStock(anyLong(), any());
    }

    @Test
    void mockPayWithoutSignatureFailsAndRecordsCallbackLog() {
        MockPaymentRequest request = new MockPaymentRequest("PAY1", true, null, null, null);
        doThrow(new BusinessException(ErrorMessageConstant.PAYMENT_SIGNATURE_INVALID))
                .when(paymentSignatureService).verify(any());

        assertThatThrownBy(() -> paymentService.mockPay(request, "{\"paymentNo\":\"PAY1\"}", java.util.Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_SIGNATURE_INVALID);

        ArgumentCaptor<PaymentCallbackLog> logCaptor = ArgumentCaptor.forClass(PaymentCallbackLog.class);
        verify(paymentAuditService).recordCallbackLog(logCaptor.capture());
        assertThat(logCaptor.getValue().getPaymentNo()).isEqualTo("PAY1");
        assertThat(logCaptor.getValue().getVerifyResult()).isEqualTo("FAILED");
        verify(paymentMapper, never()).markSuccess(anyString(), any(), any());
    }

    @Test
    void mockPayExpiredTimestampFailsAndRecordsCallbackLog() {
        MockPaymentRequest request = new MockPaymentRequest("PAY1", true, 1L, "nonce", "bad");
        doThrow(new BusinessException(ErrorMessageConstant.PAYMENT_CALLBACK_EXPIRED))
                .when(paymentSignatureService).verify(any());

        assertThatThrownBy(() -> paymentService.mockPay(request, "{\"paymentNo\":\"PAY1\"}", java.util.Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(ErrorMessageConstant.PAYMENT_CALLBACK_EXPIRED);

        verify(paymentAuditService).recordCallbackLog(any(PaymentCallbackLog.class));
        verify(paymentMapper, never()).markSuccess(anyString(), any(), any());
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
        order.setShowTitle("测试演唱会");
        order.setSessionStartTime(now.plusDays(1));
        order.setTicketCategoryName("内场票");
        order.setTicketPrice(new BigDecimal("880.00"));
        order.setTotalAmount(new BigDecimal("880.00"));
        order.setStatus(status);
        order.setExpireTime(now.plusMinutes(10));
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        return order;
    }

    private PaymentOrder payment(String paymentNo, Long orderId, String status) {
        LocalDateTime now = LocalDateTime.now();
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setId(100L);
        paymentOrder.setPaymentNo(paymentNo);
        paymentOrder.setOrderId(orderId);
        paymentOrder.setUserId(1L);
        paymentOrder.setAmount(new BigDecimal("880.00"));
        paymentOrder.setChannel("MOCK");
        paymentOrder.setStatus(status);
        paymentOrder.setCreatedAt(now);
        paymentOrder.setUpdatedAt(now);
        return paymentOrder;
    }

    private TicketOrderRequest bucketOrderRequest(Long orderId, Integer bucketVersion, Integer bucketNo) {
        TicketOrderRequest request = new TicketOrderRequest();
        request.setId(10L);
        request.setRequestId("REQ1");
        request.setOrderId(orderId);
        request.setTicketCategoryId(2L);
        request.setQuantity(1);
        request.setStockBucketVersion(bucketVersion);
        request.setStockBucketNo(bucketNo);
        request.setRedisDeducted(true);
        request.setDeductedQuantity(1);
        return request;
    }

    private PaymentServiceImpl bucketEnabledPaymentService() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(true);
        return new PaymentServiceImpl(
                paymentMapper,
                orderMapper,
                orderRequestMapper,
                ticketStockMapper,
                ticketStockBucketMapper,
                paymentSignatureService,
                paymentAuditService,
                observabilityMetricsService,
                properties
        );
    }

    private MockPaymentRequest signedRequest(String paymentNo, boolean success) {
        return new MockPaymentRequest(paymentNo, success, System.currentTimeMillis(), "nonce-" + paymentNo + "-" + success, "signature");
    }
}
