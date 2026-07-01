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
import com.zewbby.smartticket.domain.vo.PaymentVO;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.enums.PaymentCallbackResultEnum;
import com.zewbby.smartticket.enums.PaymentChannelEnum;
import com.zewbby.smartticket.enums.PaymentFlowEventTypeEnum;
import com.zewbby.smartticket.enums.PaymentStatusEnum;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.PaymentMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mq.PaymentCompensationMessage;
import com.zewbby.smartticket.service.DomainEventPublisher;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.PaymentAuditService;
import com.zewbby.smartticket.service.PaymentCompensationPublisher;
import com.zewbby.smartticket.service.PaymentService;
import com.zewbby.smartticket.service.PaymentSignatureService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final String MOCK_FAIL_REASON = "模拟支付失败";

    private static final DateTimeFormatter PAYMENT_NO_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final PaymentMapper paymentMapper;

    private final OrderMapper orderMapper;

    private final OrderRequestMapper orderRequestMapper;

    private final TicketStockMapper ticketStockMapper;

    private final TicketStockBucketMapper ticketStockBucketMapper;

    private final PaymentSignatureService paymentSignatureService;

    private final PaymentAuditService paymentAuditService;

    private final ObservabilityMetricsService observabilityMetricsService;

    private final StockBucketProperties stockBucketProperties;

    @Autowired(required = false)
    private PaymentCompensationPublisher paymentCompensationPublisher;

    @Autowired(required = false)
    private DomainEventPublisher domainEventPublisher;

    @Autowired
    public PaymentServiceImpl(PaymentMapper paymentMapper,
                              OrderMapper orderMapper,
                              OrderRequestMapper orderRequestMapper,
	                              TicketStockMapper ticketStockMapper,
	                              TicketStockBucketMapper ticketStockBucketMapper,
	                              PaymentSignatureService paymentSignatureService,
	                              PaymentAuditService paymentAuditService,
	                              ObservabilityMetricsService observabilityMetricsService,
	                              StockBucketProperties stockBucketProperties) {
        this.paymentMapper = paymentMapper;
        this.orderMapper = orderMapper;
        this.orderRequestMapper = orderRequestMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.ticketStockBucketMapper = ticketStockBucketMapper;
        this.paymentSignatureService = paymentSignatureService;
        this.paymentAuditService = paymentAuditService;
        this.observabilityMetricsService = observabilityMetricsService;
        this.stockBucketProperties = stockBucketProperties;
    }

    public PaymentServiceImpl(PaymentMapper paymentMapper,
                              OrderMapper orderMapper,
                              TicketStockMapper ticketStockMapper,
                              PaymentSignatureService paymentSignatureService,
                              PaymentAuditService paymentAuditService,
                              ObservabilityMetricsService observabilityMetricsService) {
        this(paymentMapper,
                orderMapper,
                null,
                ticketStockMapper,
                null,
                paymentSignatureService,
                paymentAuditService,
                observabilityMetricsService,
                disabledBucketProperties());
    }

    private static StockBucketProperties disabledBucketProperties() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(false);
        return properties;
    }

    @Override
    @Transactional
    public PaymentVO createPayment(CreatePaymentRequest request) {
        /*
         * 创建支付单必须从登录态拿 userId，并按 orderId + userId 查询订单。
         * 前端只能告诉我们“想支付哪个订单”，不能告诉我们金额，也不能替换订单归属。
         */
        Long currentUserId = UserContext.requireUserId();
        String channel = PaymentChannelEnum.normalize(request.getChannel());
        if (channel == null) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_CHANNEL_NOT_SUPPORTED);
        }

        TicketOrder order = orderMapper.selectByIdAndUserId(request.getOrderId(), currentUserId);
        if (order == null) {
            throw new BusinessException(ErrorMessageConstant.ORDER_NOT_FOUND);
        }

        /*
         * 当前阶段一个订单只允许一个 payment_order。
         * 重复创建支付单时返回已有支付单，而不是再插一条，避免同一订单出现多笔 INIT 支付单。
         */
        PaymentOrder existingPayment = paymentMapper.selectByOrderIdAndUserId(order.getId(), currentUserId);
        if (existingPayment != null) {
            return toPaymentVO(existingPayment);
        }

        validateOrderCanCreatePayment(order);

        LocalDateTime now = LocalDateTime.now();
        PaymentOrder paymentOrder = new PaymentOrder();
        paymentOrder.setPaymentNo(generatePaymentNo());
        paymentOrder.setOrderId(order.getId());
        paymentOrder.setUserId(currentUserId);
        paymentOrder.setAmount(order.getTotalAmount());
        paymentOrder.setChannel(channel);
        paymentOrder.setStatus(PaymentStatusEnum.INIT.getCode());
        paymentOrder.setPaidAt(null);
        paymentOrder.setCallbackAt(null);
        paymentOrder.setClosedAt(null);
        paymentOrder.setFailReason(null);
        paymentOrder.setCreatedAt(now);
        paymentOrder.setUpdatedAt(now);

        /*
         * payment_order.amount 来自 ticket_order.total_amount。
         * total_amount 是下单时的金额快照，后续后台修改票档价格，也不能影响历史订单应付金额。
         */
        try {
            int insertRows = paymentMapper.insert(paymentOrder);
            if (insertRows != 1) {
                throw new BusinessException(ErrorMessageConstant.PAYMENT_CREATE_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            PaymentOrder duplicatedPayment = paymentMapper.selectByOrderIdAndUserId(order.getId(), currentUserId);
            if (duplicatedPayment != null) {
                return toPaymentVO(duplicatedPayment);
            }
            throw exception;
        }

        /*
         * 支付金额只能来自订单快照 total_amount，不能由前端传入。
         * 这里写支付流水是为了保留 payment_order 从无到 INIT 的审计证据，后续排查“是否重复创建支付单”
         * 不能只靠 payment_order 当前状态。
         */
        recordFlow(paymentOrder,
                null,
                PaymentStatusEnum.INIT.getCode(),
                PaymentFlowEventTypeEnum.CREATE_PAYMENT,
                PaymentCallbackResultEnum.SUCCESS,
                "创建支付单，金额来自订单快照");

        return toPaymentVO(paymentOrder);
    }

    /**
     * 查询支付单详情
     * @param paymentNo
     * @return
     */
    @Override
    public PaymentVO getPayment(String paymentNo) {
        Long currentUserId = UserContext.requireUserId();
        //鉴权确认该支付单属于当前用户
        PaymentOrder paymentOrder = paymentMapper.selectByPaymentNoAndUserId(paymentNo, currentUserId);
        if (paymentOrder == null) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_NOT_FOUND);
        }
        return toPaymentVO(paymentOrder);
    }

    /**
     * 发起模拟支付请求
     * @param request
     * @return
     */
    @Override
    @Transactional
    public PaymentVO mockPay(MockPaymentRequest request) {
        return mockPay(request, null, Map.of());
    }

    @Override
    @Transactional
    public PaymentVO mockPay(MockPaymentRequest request, String rawBody, Map<String, String> headers) {
        PaymentOrder paymentForLog = null;
        boolean verifySuccess = false;
        String processResult = PaymentCallbackResultEnum.FAILED.name();
        String errorMessage = null;
        LocalDateTime callbackTime = LocalDateTime.now();

        try {
            /*
             * mock-pay 是模拟支付，不是真实第三方支付；但它仍然会改变订单和库存。
             * 因此不能让任何登录用户只凭 paymentNo 裸调成功，必须先校验内部签名和时间窗口。
             */
            paymentSignatureService.verify(request);
            verifySuccess = true;

            if (request == null) {
                throw new BusinessException(ErrorMessageConstant.PAYMENT_SIGNATURE_INVALID);
            }

            Long currentUserId = UserContext.requireUserId();
            paymentForLog = paymentMapper.selectByPaymentNoAndUserId(request.getPaymentNo(), currentUserId);
            if (paymentForLog == null) {
                throw new BusinessException(ErrorMessageConstant.PAYMENT_NOT_FOUND);
            }

            PaymentVO result = handleMockCallback(request.getPaymentNo(), Boolean.TRUE.equals(request.getSuccess()));
            processResult = PaymentCallbackResultEnum.SUCCESS.name();
            return result;
        } catch (RuntimeException exception) {
            errorMessage = exception.getMessage();
            publishPaymentCompensationIfNecessary(request, paymentForLog, verifySuccess, errorMessage);
            throw exception;
        } finally {
            recordCallbackLog(request, paymentForLog, rawBody, headers, verifySuccess, processResult, errorMessage, callbackTime);
        }
    }

    @Override
    @Transactional
    public void compensateMockPay(String paymentNo, Long userId, boolean success) {
        if (paymentNo == null || paymentNo.isBlank() || userId == null) {
            return;
        }
        Long previousUserId = UserContext.getUserId();
        try {
            UserContext.setUserId(userId);
            PaymentOrder paymentOrder = paymentMapper.selectByPaymentNoAndUserId(paymentNo, userId);
            if (paymentOrder == null) {
                return;
            }
            handleMockCallback(paymentNo, success);
        } finally {
            if (previousUserId == null) {
                UserContext.clear();
            } else {
                UserContext.setUserId(previousUserId);
            }
        }
    }

    /**
     * 处理模拟支付平台回调。
     *
     * Controller 负责登录态和支付单归属校验；这里承载真正的支付状态机逻辑。
     * 后续如果要接第三方回调，也应该先做验签和回调原文落库，再进入这个内部处理方法。
     */
    private PaymentVO handleMockCallback(String paymentNo, boolean success) {
        PaymentOrder paymentOrder = paymentMapper.selectByPaymentNo(paymentNo);
        if (paymentOrder == null) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_NOT_FOUND);
        }
        if (success) {
            return handleSuccess(paymentOrder);
        }
        return handleFailure(paymentOrder);
    }

    /**
     * 处理支付成功逻辑（包含支付状态更新、订单状态更新、库存扣减确认）
     * @param paymentOrder
     * @return
     */
    private PaymentVO handleSuccess(PaymentOrder paymentOrder) {
        //支付单状态幂等及合法性校验
        if (PaymentStatusEnum.isSuccess(paymentOrder.getStatus())) {
            recordFlow(paymentOrder,
                    PaymentStatusEnum.SUCCESS.getCode(),
                    PaymentStatusEnum.SUCCESS.getCode(),
                    PaymentFlowEventTypeEnum.IDEMPOTENT_REPEAT,
                    PaymentCallbackResultEnum.SUCCESS,
                    "支付成功回调重复到达，按幂等成功返回");
            return toPaymentVO(paymentOrder);
        }
        if (PaymentStatusEnum.isFailed(paymentOrder.getStatus()) || PaymentStatusEnum.isClosed(paymentOrder.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_STATUS_NOT_ALLOWED);
        }

        TicketOrder order = orderMapper.selectById(paymentOrder.getOrderId());
        if (order == null) {
            throw new BusinessException(ErrorMessageConstant.ORDER_NOT_FOUND);
        }
        //只有待支付的订单可以支付
        if (!OrderStatusEnum.isPendingPayment(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        LocalDateTime now = LocalDateTime.now();

        //更新支付单状态为成功
        int paymentRows = paymentMapper.markSuccess(paymentOrder.getPaymentNo(), now, now);
        if (paymentRows != 1) {
            // 更新失败时检查是否被并发线程更新为成功状态
            PaymentOrder latestPayment = paymentMapper.selectByPaymentNo(paymentOrder.getPaymentNo());
            if (latestPayment != null && PaymentStatusEnum.isSuccess(latestPayment.getStatus())) {
                recordFlow(latestPayment,
                        PaymentStatusEnum.SUCCESS.getCode(),
                        PaymentStatusEnum.SUCCESS.getCode(),
                        PaymentFlowEventTypeEnum.IDEMPOTENT_REPEAT,
                        PaymentCallbackResultEnum.SUCCESS,
                        "并发支付成功回调重复到达，按幂等成功返回");
                return toPaymentVO(latestPayment);
            }
            throw new BusinessException(ErrorMessageConstant.PAYMENT_STATUS_NOT_ALLOWED);
        }

        int orderRows = orderMapper.updatePayStatusByUserId(
                order.getId(),
                paymentOrder.getUserId(),
                OrderStatusEnum.PENDING_PAYMENT.getCode(),
                OrderStatusEnum.PAID.getCode(),
                now
        );
        if (orderRows != 1) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        boolean bucketStockConfirmed = confirmBucketStockIfNecessary(order);
        if (!bucketStockConfirmed) {
            // 确认扣减票务库存（将预占库存转为实际消耗库存）
            int stockRows = ticketStockMapper.confirmStock(order.getTicketCategoryId(), order.getQuantity());
            if (stockRows != 1) {
                throw new BusinessException(ErrorMessageConstant.STOCK_CONFIRM_FAILED);
            }
        }
        observabilityMetricsService.recordOrderPaid();
        publishPaymentPaidEvents(paymentOrder, order);

        /*
         * 支付流水描述 payment_order 的状态变化，不负责证明库存已经售出；库存流转仍以 ticket_stock 条件更新为准。
         * 这条流水能在排查时说明：哪个 paymentNo 因哪次 mock 回调从 INIT/PAYING 进入 SUCCESS。
         */
        recordFlow(paymentOrder,
                paymentOrder.getStatus(),
                PaymentStatusEnum.SUCCESS.getCode(),
                PaymentFlowEventTypeEnum.MOCK_CALLBACK_SUCCESS,
                PaymentCallbackResultEnum.SUCCESS,
                "模拟支付成功回调");

        return toPaymentVO(paymentMapper.selectByPaymentNo(paymentOrder.getPaymentNo()));
    }

    /**
     * 处理支付失败逻辑
     * @param paymentOrder
     * @return
     */
    private PaymentVO handleFailure(PaymentOrder paymentOrder) {
        //检测当前状态是否已被标记为失败
        if (PaymentStatusEnum.isFailed(paymentOrder.getStatus())) {
            recordFlow(paymentOrder,
                    PaymentStatusEnum.FAILED.getCode(),
                    PaymentStatusEnum.FAILED.getCode(),
                    PaymentFlowEventTypeEnum.IDEMPOTENT_REPEAT,
                    PaymentCallbackResultEnum.SUCCESS,
                    "支付失败回调重复到达，按幂等失败返回");
            return toPaymentVO(paymentOrder);
        }
        if (PaymentStatusEnum.isSuccess(paymentOrder.getStatus()) || PaymentStatusEnum.isClosed(paymentOrder.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.PAYMENT_STATUS_NOT_ALLOWED);
        }

        LocalDateTime now = LocalDateTime.now();
        //更新支付单状态为失败
        int failedRows = paymentMapper.markFailed(paymentOrder.getPaymentNo(), now, MOCK_FAIL_REASON);
        if (failedRows != 1) {
            // 解决并发更新场景
            PaymentOrder latestPayment = paymentMapper.selectByPaymentNo(paymentOrder.getPaymentNo());
            if (latestPayment != null && PaymentStatusEnum.isFailed(latestPayment.getStatus())) {
                recordFlow(latestPayment,
                        PaymentStatusEnum.FAILED.getCode(),
                        PaymentStatusEnum.FAILED.getCode(),
                        PaymentFlowEventTypeEnum.IDEMPOTENT_REPEAT,
                        PaymentCallbackResultEnum.SUCCESS,
                        "并发支付失败回调重复到达，按幂等失败返回");
                return toPaymentVO(latestPayment);
            }
            throw new BusinessException(ErrorMessageConstant.PAYMENT_STATUS_NOT_ALLOWED);
        }
        recordFlow(paymentOrder,
                paymentOrder.getStatus(),
                PaymentStatusEnum.FAILED.getCode(),
                PaymentFlowEventTypeEnum.MOCK_CALLBACK_FAILED,
                PaymentCallbackResultEnum.SUCCESS,
                MOCK_FAIL_REASON);
        return toPaymentVO(paymentMapper.selectByPaymentNo(paymentOrder.getPaymentNo()));
    }

    private boolean confirmBucketStockIfNecessary(TicketOrder order) {
        if (!stockBucketProperties.isEnabled() || orderRequestMapper == null || ticketStockBucketMapper == null) {
            return false;
        }
        TicketOrderRequest orderRequest = orderRequestMapper.selectByOrderId(order.getId());
        if (orderRequest == null || orderRequest.getStockBucketNo() == null) {
            return false;
        }
        int bucketRows = orderRequest.getStockBucketVersion() == null
                ? ticketStockBucketMapper.confirmStock(
                        order.getTicketCategoryId(),
                        orderRequest.getStockBucketNo(),
                        order.getQuantity()
                )
                : ticketStockBucketMapper.confirmStockByVersion(
                        order.getTicketCategoryId(),
                        orderRequest.getStockBucketVersion(),
                        orderRequest.getStockBucketNo(),
                        order.getQuantity()
                );
        if (bucketRows != 1) {
            throw new BusinessException(ErrorMessageConstant.STOCK_CONFIRM_FAILED);
        }
        return true;
    }

    private void publishPaymentCompensationIfNecessary(MockPaymentRequest request,
                                                       PaymentOrder paymentOrder,
                                                       boolean verifySuccess,
                                                       String errorMessage) {
        if (paymentCompensationPublisher == null
                || !verifySuccess
                || request == null
                || paymentOrder == null) {
            return;
        }
        PaymentCompensationMessage message = new PaymentCompensationMessage();
        message.setPaymentNo(paymentOrder.getPaymentNo());
        message.setOrderId(paymentOrder.getOrderId());
        message.setUserId(paymentOrder.getUserId());
        message.setSuccess(Boolean.TRUE.equals(request.getSuccess()));
        message.setReason(truncate(errorMessage, 512));
        paymentCompensationPublisher.publish(message);
    }

    private void publishPaymentPaidEvents(PaymentOrder paymentOrder, TicketOrder order) {
        if (domainEventPublisher == null || paymentOrder == null || order == null) {
            return;
        }
        domainEventPublisher.publishPaymentPaid(paymentOrder);
        domainEventPublisher.publishStockChanged(
                order.getTicketCategoryId(),
                order.getId(),
                "PAYMENT_PAID_SOLD",
                order.getQuantity()
        );
    }

    /**
     * 校验订单是否满足创建支付单的条件
     * @param order
     */
    private void validateOrderCanCreatePayment(TicketOrder order) {
        //已支付的不能重复支付
        if (OrderStatusEnum.isPaid(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_PAY);
        }
        //不是待支付不行
        if (!OrderStatusEnum.isPendingPayment(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }
        //过期的支付订单不支付
        if (order.getExpireTime() != null && LocalDateTime.now().isAfter(order.getExpireTime())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_EXPIRED);
        }
    }

    /**
     * 生成支付流水号
     * @return
     */
    private String generatePaymentNo() {
        String timePart = LocalDateTime.now().format(PAYMENT_NO_TIME_FORMATTER);
        String randomPart = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12)
                .toUpperCase();
        return "PAY" + timePart + randomPart;
    }

    private void recordCallbackLog(MockPaymentRequest request,
                                   PaymentOrder paymentOrder,
                                   String rawBody,
                                   Map<String, String> headers,
                                   boolean verifySuccess,
                                   String processResult,
                                   String errorMessage,
                                   LocalDateTime callbackTime) {
        /*
         * 回调原文日志记录的是“系统收到过什么”，不是“支付一定成功”。
         * 签名失败、timestamp 过期、nonce 重放、重复回调都要记录；但不能记录 secret/token/password。
         * Controller 已经对 header 做脱敏，这里只保存脱敏后的 header 文本。
         */
        PaymentCallbackLog callbackLog = new PaymentCallbackLog();
        callbackLog.setPaymentNo(request == null ? null : request.getPaymentNo());
        callbackLog.setOrderId(paymentOrder == null ? null : paymentOrder.getOrderId());
        callbackLog.setUserId(paymentOrder == null ? null : paymentOrder.getUserId());
        callbackLog.setChannel(paymentOrder == null ? null : paymentOrder.getChannel());
        callbackLog.setRawBody(rawBody);
        callbackLog.setHeaders(headers == null ? null : headers.toString());
        callbackLog.setSignature(request == null ? null : request.getSignature());
        callbackLog.setVerifyResult(verifySuccess
                ? PaymentCallbackResultEnum.SUCCESS.name()
                : PaymentCallbackResultEnum.FAILED.name());
        callbackLog.setProcessResult(processResult);
        callbackLog.setErrorMessage(truncate(errorMessage, 512));
        callbackLog.setCallbackTime(callbackTime);
        callbackLog.setCreatedAt(LocalDateTime.now());
        paymentAuditService.recordCallbackLog(callbackLog);
    }

    private void recordFlow(PaymentOrder paymentOrder,
                            String fromStatus,
                            String toStatus,
                            PaymentFlowEventTypeEnum eventType,
                            PaymentCallbackResultEnum result,
                            String reason) {
        /*
         * payment_flow_log 记录 payment_order 状态如何变化。
         * 它不是替代 payment_order 的状态字段，而是用于排查：什么时候创建、什么时候成功/失败、
         * 是否发生过幂等重复回调，以及取消/超时关闭时支付单是否被联动关闭。
         */
        PaymentFlowLog flowLog = new PaymentFlowLog();
        flowLog.setPaymentNo(paymentOrder.getPaymentNo());
        flowLog.setOrderId(paymentOrder.getOrderId());
        flowLog.setFromStatus(fromStatus);
        flowLog.setToStatus(toStatus);
        flowLog.setEventType(eventType.name());
        flowLog.setAmount(paymentOrder.getAmount());
        flowLog.setResult(result.name());
        flowLog.setReason(truncate(reason, 512));
        flowLog.setCreatedAt(LocalDateTime.now());
        paymentAuditService.recordFlowLog(flowLog);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private PaymentVO toPaymentVO(PaymentOrder paymentOrder) {
        PaymentVO paymentVO = new PaymentVO();
        BeanUtils.copyProperties(paymentOrder, paymentVO);
        return paymentVO;
    }
}
