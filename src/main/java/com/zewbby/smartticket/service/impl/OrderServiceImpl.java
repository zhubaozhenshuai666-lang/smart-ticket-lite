package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.cache.OrderSubmitGuard;
import com.zewbby.smartticket.service.StockLuaService;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.domain.dto.CreateOrderRequest;
import com.zewbby.smartticket.domain.dto.OrderSnapshot;
import com.zewbby.smartticket.domain.entity.PaymentFlowLog;
import com.zewbby.smartticket.domain.entity.PaymentOrder;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.domain.vo.OrderVO;
import com.zewbby.smartticket.enums.CompensationStatusEnum;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.enums.PaymentCallbackResultEnum;
import com.zewbby.smartticket.enums.PaymentFlowEventTypeEnum;
import com.zewbby.smartticket.enums.PaymentStatusEnum;
import com.zewbby.smartticket.enums.RedisStockDeductResult;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.idempotency.IdempotencyTokenService;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.PaymentMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.mq.OrderTimeoutProducer;
import com.zewbby.smartticket.ratelimit.RateLimitService;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.OrderService;
import com.zewbby.smartticket.service.PaymentAuditService;
import com.zewbby.smartticket.service.StockCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final String USER_CANCEL_REASON = "用户主动取消";

    private static final String TIMEOUT_CLOSE_REASON = "订单超时未支付关闭";

    private static final String SYNC_ORDER_API_NAME = "orders:create";

    private static final String ASYNC_ORDER_API_NAME = "orders:async";

    private static final String UNKNOWN_CLIENT_IP = "unknown";

    private final OrderMapper orderMapper;

    private final OrderRequestMapper orderRequestMapper;

    private final PaymentMapper paymentMapper;

    private final UserMapper userMapper;

    private final TicketCategoryMapper ticketCategoryMapper;

    private final TicketStockMapper ticketStockMapper;

    private final OrderSubmitGuard orderSubmitGuard;

    private final OrderTimeoutProducer orderTimeoutProducer;

    private final RateLimitService rateLimitService;

    private final IdempotencyTokenService idempotencyTokenService;

    private final StockLuaService stockLuaService;

    private final StockCacheService stockCacheService;

    private final AsyncOrderMessagePublisher asyncOrderMessagePublisher;

    private final PaymentAuditService paymentAuditService;

    private final ObservabilityMetricsService observabilityMetricsService;

    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderRequestMapper orderRequestMapper,
                            PaymentMapper paymentMapper,
                            UserMapper userMapper,
                            TicketCategoryMapper ticketCategoryMapper,
                            TicketStockMapper ticketStockMapper,
                            OrderSubmitGuard orderSubmitGuard,
                            OrderTimeoutProducer orderTimeoutProducer,
                            RateLimitService rateLimitService,
                            IdempotencyTokenService idempotencyTokenService,
                            StockLuaService stockLuaService,
	                            StockCacheService stockCacheService,
	                            AsyncOrderMessagePublisher asyncOrderMessagePublisher,
	                            PaymentAuditService paymentAuditService,
	                            ObservabilityMetricsService observabilityMetricsService) {
        this.orderMapper = orderMapper;
        this.orderRequestMapper = orderRequestMapper;
        this.paymentMapper = paymentMapper;
        this.userMapper = userMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.orderSubmitGuard = orderSubmitGuard;
        this.orderTimeoutProducer = orderTimeoutProducer;
        this.rateLimitService = rateLimitService;
        this.idempotencyTokenService = idempotencyTokenService;
        this.stockLuaService = stockLuaService;
        this.stockCacheService = stockCacheService;
        this.asyncOrderMessagePublisher = asyncOrderMessagePublisher;
        this.paymentAuditService = paymentAuditService;
        this.observabilityMetricsService = observabilityMetricsService;
    }

    /**
     * 提交异步订单请求，并在入口阶段完成 Redis 预扣库存。
     *
     * 这个方法解决高并发下“所有请求都直接进入 MySQL 扣库存”的压力问题：
     * 入口先创建 INIT 请求记录，再用 requestId 调 Redis Lua 原子预扣库存。Redis 扣成功只表示抢购资格暂时占住，
     * 还不代表正式订单已创建；消费者仍然要做 MySQL 条件扣库存作为最终持久化保护。
     *
     * 成功返回 QUEUED/PRE_DEDUCTED 之后，用户只能拿 requestId 查询后续处理结果。
     * 如果 Redis 预扣失败，请求会标记 FAILED，不会进入消息发送链路。
     * 如果 Redis 已扣但消息发送等后续步骤失败，本方法会立即释放 Redis 预扣，并把请求标记为 COMPENSATED，
     * 避免可抢库存被长期占住。
     *
     * @param request 下单参数，userId 字段不可信，真实用户必须来自 UserContext。
     * @return 异步下单请求视图，包含 requestId 和当前状态。
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrderRequestVO submitAsyncOrder(CreateOrderRequest request) {
        return submitAsyncOrder(request, UNKNOWN_CLIENT_IP);
    }

    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrderRequestVO submitAsyncOrder(CreateOrderRequest request, String clientIp) {
        Long currentUserId = UserContext.requireUserId();
        checkCoarseOrderSubmitRateLimit(currentUserId, clientIp, ASYNC_ORDER_API_NAME);
        checkSoldoutFastFail(request.getTicketCategoryId());

        if (!orderSubmitGuard.tryAcquire(currentUserId, request.getTicketCategoryId())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_SUBMIT);
        }

        TicketOrderRequest orderRequest = null;
        boolean redisPreDeducted = false;
        try {
            UserAccount user = userMapper.selectById(currentUserId);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            //验证一致性
            validateShowSessionTicketCategoryRelation(request);
            checkTicketOrderSubmitRateLimit(request);
            //用lua消耗token
            idempotencyTokenService.consumeOrderToken(currentUserId, request.getIdempotencyToken());

            LocalDateTime now = LocalDateTime.now();
            String requestId = generateRequestId();

            orderRequest = new TicketOrderRequest();
            orderRequest.setRequestId(requestId);
            orderRequest.setUserId(currentUserId);
            orderRequest.setShowId(request.getShowId());
            orderRequest.setSessionId(request.getSessionId());
            orderRequest.setTicketCategoryId(request.getTicketCategoryId());
            orderRequest.setQuantity(request.getQuantity());
            orderRequest.setStatus(OrderRequestStatusEnum.INIT.getCode());
            orderRequest.setOrderId(null);
            orderRequest.setProcessingAt(null);
            orderRequest.setRedisDeducted(false);
            orderRequest.setDeductedQuantity(0);
            orderRequest.setDeductedAt(null);
            orderRequest.setCompensated(false);
            orderRequest.setCompensationStatus(CompensationStatusEnum.NONE.getCode());
            orderRequest.setCompensatedAt(null);
            orderRequest.setFailReason(null);
            orderRequest.setMessageId(null);
            orderRequest.setCreatedAt(now);
            orderRequest.setUpdatedAt(now);

            // INIT 记录先落库，后续 Redis 预扣失败也能留下明确失败状态，便于排查和后续补偿巡检。
            int insertRows = orderRequestMapper.insert(orderRequest);
            if (insertRows != 1) {
                throw new BusinessException("异步下单请求创建失败");
            }

            RedisStockDeductResult deductResult = stockLuaService.preDeductStock(
                    requestId,
                    request.getTicketCategoryId(),
                    request.getQuantity()
            );
            if (!deductResult.isSuccess()) {
                orderRequestMapper.markFailed(orderRequest.getId(), toPreDeductFailMessage(deductResult));
                observabilityMetricsService.recordAsyncOrderRequestFailed();
                throw new BusinessException(toPreDeductFailMessage(deductResult));
            }
            redisPreDeducted = true;

            int preDeductedRows = orderRequestMapper.markPreDeducted(
                    orderRequest.getId(),
                    request.getQuantity(),
                    now
            );
            if (preDeductedRows != 1) {
                throw new BusinessException("异步下单请求预扣状态更新失败");
            }
            orderRequest.setStatus(OrderRequestStatusEnum.PRE_DEDUCTED.getCode());
            orderRequest.setRedisDeducted(true);
            orderRequest.setDeductedQuantity(request.getQuantity());
            orderRequest.setDeductedAt(now);

            //整个message
            AsyncCreateOrderMessage message = new AsyncCreateOrderMessage(
                    requestId,
                    currentUserId,
                    request.getShowId(),
                    request.getSessionId(),
                    request.getTicketCategoryId(),
                    request.getQuantity()
            );
            String messageId = asyncOrderMessagePublisher.publish(message);
            int queuedRows = orderRequestMapper.markQueued(orderRequest.getId(), messageId);
            if (queuedRows != 1) {
                throw new BusinessException("异步下单请求排队状态更新失败");
            }
            orderRequest.setStatus(OrderRequestStatusEnum.QUEUED.getCode());
            orderRequest.setMessageId(messageId);

            return toOrderRequestVO(orderRequest);
        } catch (RuntimeException exception) {
            releaseRedisPreDeductedStockAfterSubmitFailure(orderRequest, redisPreDeducted, "异步下单提交失败");
            //发生其他意外就释放锁
            orderSubmitGuard.release(currentUserId, request.getTicketCategoryId());
            throw exception;
        }
    }

    @Override
    public OrderRequestVO getOrderRequestResult(String requestId) {
        Long currentUserId = UserContext.requireUserId();
        TicketOrderRequest orderRequest = orderRequestMapper.selectByRequestIdAndUserId(requestId, currentUserId);
        if (orderRequest == null) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REQUEST_NOT_FOUND);
        }
        return toOrderRequestVO(orderRequest);
    }

    @Override
    @Deprecated
    @Transactional
    public OrderVO createOrder(CreateOrderRequest request) {
        return createOrder(request, UNKNOWN_CLIENT_IP);
    }

    @Override
    @Deprecated
    @Transactional
    public OrderVO createOrder(CreateOrderRequest request, String clientIp) {
        /*
         * 同步下单只保留为兼容和本地调试入口，不再作为高并发抢票主链路。
         * 高并发场景如果同时宣传同步和异步两套主路径，会让限流、Redis 预扣、Outbox、消费者幂等等治理边界变得混乱。
         * 真正的抢票入口应该走 submitAsyncOrder：Redis 先削峰，local_message 再可靠投递，消费者异步创建订单。
         */
        Long currentUserId = UserContext.requireUserId();
        //限流
        checkCoarseOrderSubmitRateLimit(currentUserId, clientIp, SYNC_ORDER_API_NAME);
        checkSoldoutFastFail(request.getTicketCategoryId());

        //防重复提交
        if (!orderSubmitGuard.tryAcquire(currentUserId, request.getTicketCategoryId())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_SUBMIT);
        }

        try {
            UserAccount user = userMapper.selectById(currentUserId);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            OrderSnapshot snapshot = getOrderSnapshot(request);
            checkTicketOrderSubmitRateLimit(request);
            idempotencyTokenService.consumeOrderToken(currentUserId, request.getIdempotencyToken());

            TicketStock ticketStock = ticketStockMapper.selectByTicketCategoryId(request.getTicketCategoryId());
            if (ticketStock == null) {
                throw new BusinessException("库存记录不存在");
            }

            int decreaseRows = ticketStockMapper.decreaseStock(request.getTicketCategoryId(), request.getQuantity());
            if (decreaseRows == 0) {
                throw new BusinessException("库存不足");
            }

            BigDecimal totalAmount = calculateTotalAmount(snapshot.getTicketPrice(), request.getQuantity());
            LocalDateTime now = LocalDateTime.now();

            TicketOrder order = new TicketOrder();
            order.setOrderNo(generateOrderNo());
            order.setUserId(currentUserId);
            order.setShowId(request.getShowId());
            order.setSessionId(request.getSessionId());
            order.setTicketCategoryId(request.getTicketCategoryId());
            order.setQuantity(request.getQuantity());
            fillOrderSnapshot(order, snapshot);
            order.setTotalAmount(totalAmount);
            order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode());
            order.setExpireTime(now.plusMinutes(OrderConstant.ORDER_TIMEOUT_MINUTES));
            order.setPayTime(null);
            order.setCancelTime(null);
            order.setCloseTime(null);
            order.setCancelReason(null);
            order.setCreatedAt(now);
            order.setUpdatedAt(now);

            int insertRows = orderMapper.insert(order);
            if (insertRows != 1) {
                throw new BusinessException("订单创建失败");
            }
            observabilityMetricsService.recordOrderCreated();

            orderTimeoutProducer.sendOrderTimeoutMessage(buildOrderTimeoutMessage(order));
            return toOrderVO(order);
        } finally {
            orderSubmitGuard.release(currentUserId, request.getTicketCategoryId());
        }
    }

    private OrderSnapshot getOrderSnapshot(CreateOrderRequest request) {
        validateShowSessionTicketCategoryRelation(request);
        OrderSnapshot snapshot = ticketCategoryMapper.selectOrderSnapshot(
                request.getShowId(),
                request.getSessionId(),
                request.getTicketCategoryId()
        );
        if (snapshot == null) {
            throw new BusinessException(ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        }
        return snapshot;
    }

    private void validateShowSessionTicketCategoryRelation(CreateOrderRequest request) {
        boolean relationExists = ticketCategoryMapper.existsShowSessionTicketCategoryRelation(
                request.getShowId(),
                request.getSessionId(),
                request.getTicketCategoryId()
        );
        if (!relationExists) {
            throw new BusinessException(ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);
        }
    }

    @Override
    public OrderVO getOrderById(Long orderId) {
        return toOrderVO(getExistingUserOrder(orderId, UserContext.requireUserId()));
    }

    @Override
    public List<OrderVO> listCurrentUserOrders() {
        return orderMapper.selectByUserId(UserContext.requireUserId())
                .stream()
                .map(this::toOrderVO)
                .collect(Collectors.toList());
    }

    /**
     * 取消订单
     * @param orderId
     * @return
     */
    @Override
    @Transactional
    public OrderVO cancelOrder(Long orderId) {
        Long currentUserId = UserContext.requireUserId();
        TicketOrder order = getExistingUserOrder(orderId, currentUserId);
        //不可以重复取消订单
        if (OrderStatusEnum.isCancelled(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_CANCEL);
        }
        //只有待支付的订单能被取消
        if (!OrderStatusEnum.isPendingPayment(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        int updateRows = orderMapper.updateCancelStatusByUserId(
                orderId,
                currentUserId,
                OrderStatusEnum.PENDING_PAYMENT.getCode(),
                OrderStatusEnum.CANCELLED.getCode(),
                LocalDateTime.now(),
                USER_CANCEL_REASON
        );
        if (updateRows != 1) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        //取消订单的时候要回滚库存
        int rollbackRows = ticketStockMapper.rollbackStock(order.getTicketCategoryId(), order.getQuantity());
        if (rollbackRows != 1) {
            throw new BusinessException("库存回滚失败");
        }
        observabilityMetricsService.recordOrderCancelled();

        closeUnpaidPaymentWithFlow(orderId);
        rollbackRedisStockIfAsyncOrder(order);

        return toOrderVO(orderMapper.selectByIdAndUserId(orderId, currentUserId));
    }

    /**
     * 支付订单
     * @param orderId
     * @return
     */
    @Override
    @Transactional
    public OrderVO payOrder(Long orderId) {
        throw new BusinessException(ErrorMessageConstant.PAYMENT_REQUIRED);
    }

    /**
     * 取消超时订单
     * @param orderId
     */
    @Override
    @Transactional
    public void closeTimeoutOrder(Long orderId) {
        TicketOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            LOGGER.warn("Ignored timeout close for missing order, orderId={}", orderId);
            return;
        }
        if (!OrderStatusEnum.isPendingPayment(order.getStatus())) {
            LOGGER.info("Skipped timeout close because order is not pending payment, orderId={}, status={}",
                    orderId, order.getStatus());
            return;
        }

        /*
         * 超时关闭必须以数据库订单状态为准，不能信任消息 payload 直接改状态。
         * 延迟消息可能晚到或重复到：订单如果已经 PAID，就绝不能被 CLOSED 覆盖；如果已 CANCELLED/CLOSED，也不能重复释放库存。
         * 这里 SQL 带旧状态条件，只有 PENDING_PAYMENT -> CLOSED 成功后，才允许释放 locked_stock 并关闭未支付 payment_order。
         */
        int updateRows = orderMapper.updateCloseStatus(
                orderId,
                OrderStatusEnum.PENDING_PAYMENT.getCode(),
                OrderStatusEnum.CLOSED.getCode(),
                LocalDateTime.now(),
                TIMEOUT_CLOSE_REASON
        );
        if (updateRows != 1) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        // 订单超时关闭后必须释放 locked_stock 回 available_stock，否则用户未支付也会长期占用库存。
        int rollbackRows = ticketStockMapper.rollbackStock(order.getTicketCategoryId(), order.getQuantity());
        if (rollbackRows != 1) {
            throw new BusinessException("库存回滚失败");
        }

        // 未支付支付单要一起关闭；已经 SUCCESS 的支付单不会被这个 SQL 覆盖。
        closeUnpaidPaymentWithFlow(orderId);
        rollbackRedisStockIfAsyncOrder(order);
    }

    private OrderTimeoutMessage buildOrderTimeoutMessage(TicketOrder order) {
        OrderTimeoutMessage message = new OrderTimeoutMessage();
        message.setOrderId(order.getId());
        message.setOrderNo(order.getOrderNo());
        message.setUserId(order.getUserId());
        message.setExpireTime(order.getExpireTime());
        message.setTraceId(null);
        message.setMessageId(null);
        return message;
    }

    /**
     * 算总金额
     * @param price
     * @param quantity
     * @return
     */
    private BigDecimal calculateTotalAmount(BigDecimal price, Integer quantity) {
        if (price == null) {
            throw new BusinessException("票档价格不存在");
        }
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 生成订单的编号
     * @return
     */
    private String generateOrderNo() {
        return "ST" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }

    private String generateRequestId() {
        return "REQ" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }

    private void rollbackRedisStockIfAsyncOrder(TicketOrder order) {
        TicketOrderRequest orderRequest = orderRequestMapper.selectByOrderId(order.getId());
        if (orderRequest == null) {
            return;
        }
        try {
            stockLuaService.rollbackStock(order.getTicketCategoryId(), order.getQuantity());
            LOGGER.info("Rolled back Redis stock for async order, orderId={}, requestId={}, ticketCategoryId={}, quantity={}",
                    order.getId(),
                    orderRequest.getRequestId(),
                    order.getTicketCategoryId(),
                    order.getQuantity());
        } catch (RuntimeException exception) {
            LOGGER.warn("Skipped Redis stock rollback for async order, orderId={}, requestId={}, ticketCategoryId={}, quantity={}, reason={}",
                    order.getId(),
                    orderRequest.getRequestId(),
                    order.getTicketCategoryId(),
                    order.getQuantity(),
                    exception.getMessage());
        }
    }

    private void releaseRedisPreDeductedStockAfterSubmitFailure(TicketOrderRequest orderRequest,
                                                               boolean redisPreDeducted,
                                                               String reason) {
        if (!redisPreDeducted) {
            return;
        }
        if (orderRequest == null) {
            return;
        }
        releaseRedisPreDeductedStock(orderRequest, reason);
    }

    /**
     * 释放异步下单入口已经预扣的 Redis 库存。
     *
     * Redis 预扣成功后，如果消息链路或请求状态更新失败，系统会处在“Redis 已扣、MySQL 正式订单未落库”的中间态。
     * 这个中间态如果不处理，用户没有订单，但 Redis 可售库存被占住；如果重复释放，又会把 Redis 库存加多。
     * 所以这里调用带 requestId 的 release Lua：它只会释放存在 deducted key 的请求，并写 compensated key 防重。
     *
     * @param orderRequest 已创建的异步下单请求。
     * @param reason 失败原因，用于日志和 request 状态记录。
     */
    private void releaseRedisPreDeductedStock(TicketOrderRequest orderRequest, String reason) {
        orderRequestMapper.markFailed(orderRequest.getId(), reason);
        try {
            RedisStockReleaseResult releaseResult = stockLuaService.releasePreDeductedStock(
                    orderRequest.getRequestId(),
                    orderRequest.getTicketCategoryId(),
                    orderRequest.getQuantity()
            );
            if (releaseResult.isSuccess() || releaseResult == RedisStockReleaseResult.ALREADY_COMPENSATED) {
                orderRequestMapper.markCompensated(orderRequest.getId(), LocalDateTime.now());
            }
            observabilityMetricsService.recordAsyncOrderRequestFailed();
            LOGGER.info("Released Redis pre-deducted stock after async submit failure, requestId={}, result={}, reason={}",
                    orderRequest.getRequestId(), releaseResult, reason);
        } catch (RuntimeException releaseException) {
            LOGGER.error("Failed to release Redis pre-deducted stock after async submit failure, requestId={}, reason={}",
                    orderRequest.getRequestId(), reason, releaseException);
        }
    }

    private String toPreDeductFailMessage(RedisStockDeductResult deductResult) {
        if (deductResult == RedisStockDeductResult.STOCK_NOT_FOUND) {
            return ErrorMessageConstant.STOCK_NOT_PREHEATED;
        }
        if (deductResult == RedisStockDeductResult.STOCK_NOT_ENOUGH) {
            return ErrorMessageConstant.STOCK_NOT_ENOUGH;
        }
        if (deductResult == RedisStockDeductResult.DUPLICATE) {
            return ErrorMessageConstant.ORDER_REPEAT_SUBMIT;
        }
        return deductResult.getMessage();
    }

    /**
     * 下单入口粗粒度限流。
     *
     * 这一步只依赖当前登录用户、客户端 IP 和接口名，不需要查库，因此可以尽早挡住刷接口流量。
     * 用户维度防止单账号疯狂提交，IP 维度防止单来源打爆入口，API 维度保护整体下单能力。
     * 票档维度限流放到归属校验之后，避免非法 ticketCategoryId 也污染热点票档限流桶。
     */
    private void checkCoarseOrderSubmitRateLimit(Long userId, String clientIp, String apiName) {
        boolean allowed = rateLimitService.tryAcquireOrderSubmit(userId, clientIp, apiName, null, false);
        if (!allowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }
    }

    /**
     * 票档维度限流。
     *
     * 热门票档会把大量请求集中到同一个 Redis 预扣 key 和后续 MQ 链路；票档限流可以把热点资源的峰值削平。
     * 这里在 showId -> sessionId -> ticketCategoryId 归属校验之后执行，确保被计入限流桶的 ticketCategoryId
     * 至少是一个合法业务组合。
     */
    private void checkTicketOrderSubmitRateLimit(CreateOrderRequest request) {
        if (!rateLimitService.tryAcquireOrderTicket(request.getTicketCategoryId())) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }
    }

    /**
     * soldout 快速失败。
     *
     * soldout 是性能优化，不是最终库存事实。它表示最近 Redis 预扣已经发现该票档库存不足，
     * 所以后续请求没有必要再创建 ticket_order_request、写 local_message 或进入 MQ。
     * 库存预热、库存补偿和人工调整库存后必须清理该标记，否则会误杀恢复后的库存。
     */
    private void checkSoldoutFastFail(Long ticketCategoryId) {
        if (stockCacheService.isSoldOut(ticketCategoryId)) {
            observabilityMetricsService.recordSoldoutFastFail();
            throw new BusinessException(ErrorMessageConstant.TICKET_SOLD_OUT);
        }
    }

    private void fillOrderSnapshot(TicketOrder order, OrderSnapshot snapshot) {
        /*
         * 订单快照不是展示字段的重复保存，而是在保护历史交易事实。
         * 后台后续可能修改演出名、场次时间或票档价格，但已经下单的订单必须保留下单当刻的名称和价格。
         */
        order.setShowTitle(snapshot.getShowTitle());
        order.setSessionStartTime(snapshot.getSessionStartTime());
        order.setTicketCategoryName(snapshot.getTicketCategoryName());
        order.setTicketPrice(snapshot.getTicketPrice());
    }

    private void closeUnpaidPaymentWithFlow(Long orderId) {
        PaymentOrder paymentOrder = paymentMapper.selectByOrderId(orderId);
        int closeRows = paymentMapper.closeUnpaidByOrderId(orderId, LocalDateTime.now());
        if (closeRows == 1 && paymentOrder != null) {
            PaymentFlowLog flowLog = new PaymentFlowLog();
            flowLog.setPaymentNo(paymentOrder.getPaymentNo());
            flowLog.setOrderId(paymentOrder.getOrderId());
            flowLog.setFromStatus(paymentOrder.getStatus());
            flowLog.setToStatus(PaymentStatusEnum.CLOSED.getCode());
            flowLog.setEventType(PaymentFlowEventTypeEnum.CLOSE_PAYMENT.name());
            flowLog.setAmount(paymentOrder.getAmount());
            flowLog.setResult(PaymentCallbackResultEnum.SUCCESS.name());
            flowLog.setReason("订单取消或超时关闭，关闭未支付支付单");
            flowLog.setCreatedAt(LocalDateTime.now());
            paymentAuditService.recordFlowLog(flowLog);
        }
    }

    private TicketOrder getExistingUserOrder(Long orderId, Long userId) {
        TicketOrder order = orderMapper.selectByIdAndUserId(orderId, userId);
        if (order == null) {
            throw new BusinessException(ErrorMessageConstant.ORDER_NOT_FOUND);
        }
        return order;
    }

    /**
     * 转换为VO实体
     * @param order
     * @return
     */
    private OrderVO toOrderVO(TicketOrder order) {
        OrderVO orderVO = new OrderVO();
        BeanUtils.copyProperties(order, orderVO);
        return orderVO;
    }

    private OrderRequestVO toOrderRequestVO(TicketOrderRequest orderRequest) {
        OrderRequestVO orderRequestVO = new OrderRequestVO();
        BeanUtils.copyProperties(orderRequest, orderRequestVO);
        return orderRequestVO;
    }
}
