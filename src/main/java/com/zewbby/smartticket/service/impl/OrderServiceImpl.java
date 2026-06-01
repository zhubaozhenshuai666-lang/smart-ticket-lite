package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.auth.UserContext;
import com.zewbby.smartticket.cache.OrderSubmitGuard;
import com.zewbby.smartticket.service.StockLuaService;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.constant.RedisKeyConstant;
import com.zewbby.smartticket.domain.dto.CreateOrderRequest;
import com.zewbby.smartticket.domain.entity.TicketCategory;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.domain.vo.OrderVO;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.idempotency.IdempotencyTokenService;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.PaymentMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutProducer;
import com.zewbby.smartticket.ratelimit.RateLimitService;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
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

    private static final String ORDER_SUBMIT_ACTION = "order-submit";

    private static final int USER_ORDER_SUBMIT_LIMIT = 5;

    private static final int TICKET_ORDER_SUBMIT_LIMIT = 50;

    private static final long ORDER_SUBMIT_WINDOW_SECONDS = 10L;

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

    private final LocalMessageService localMessageService;

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
                            LocalMessageService localMessageService) {
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
        this.localMessageService = localMessageService;
    }

    /**
     * 提交异步订单
     * @param request
     * @return
     */
    @Override
    @Transactional
    public OrderRequestVO submitAsyncOrder(CreateOrderRequest request) {
        Long currentUserId = UserContext.requireUserId();
        checkOrderSubmitRateLimit(currentUserId, request);

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
            //用lua消耗token
            idempotencyTokenService.consumeOrderToken(currentUserId, request.getIdempotencyToken());

            //redis预扣库存（Lua）
            stockLuaService.preDeductStock(request.getTicketCategoryId(), request.getQuantity());
            redisPreDeducted = true;

            LocalDateTime now = LocalDateTime.now();
            String requestId = generateRequestId();

            orderRequest = new TicketOrderRequest();
            orderRequest.setRequestId(requestId);
            orderRequest.setUserId(currentUserId);
            orderRequest.setShowId(request.getShowId());
            orderRequest.setSessionId(request.getSessionId());
            orderRequest.setTicketCategoryId(request.getTicketCategoryId());
            orderRequest.setQuantity(request.getQuantity());
            orderRequest.setStatus(OrderRequestStatusEnum.PROCESSING.getCode());
            orderRequest.setOrderId(null);
            orderRequest.setFailReason(null);
            orderRequest.setCreatedAt(now);
            orderRequest.setUpdatedAt(now);

            //带着requestId插入一下，如果插入失败(也就是说有这个订单了)就抛异常
            int insertRows = orderRequestMapper.insert(orderRequest);
            if (insertRows != 1) {
                throw new BusinessException("异步下单请求创建失败");
            }

            //整个message
            AsyncCreateOrderMessage message = new AsyncCreateOrderMessage(
                    requestId,
                    currentUserId,
                    request.getShowId(),
                    request.getSessionId(),
                    request.getTicketCategoryId(),
                    request.getQuantity()
            );
            // 本地消息和下单请求在同一个事务中保存，由定时任务负责可靠投递 MQ。
            localMessageService.createAsyncCreateOrderMessage(message);

            return toOrderRequestVO(orderRequest);
        } catch (RuntimeException exception) {
            //提交失败的话就回滚redis库存
            rollbackRedisStockAfterSubmitFailure(request, redisPreDeducted, "异步下单提交失败");
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
    @Transactional
    public OrderVO createOrder(CreateOrderRequest request) {
        Long currentUserId = UserContext.requireUserId();
        //限流
        checkOrderSubmitRateLimit(currentUserId, request);

        //防重复提交
        if (!orderSubmitGuard.tryAcquire(currentUserId, request.getTicketCategoryId())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_SUBMIT);
        }

        try {
            UserAccount user = userMapper.selectById(currentUserId);
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            TicketCategory ticketCategory = getValidTicketCategory(request);
            idempotencyTokenService.consumeOrderToken(currentUserId, request.getIdempotencyToken());

            TicketStock ticketStock = ticketStockMapper.selectByTicketCategoryId(request.getTicketCategoryId());
            if (ticketStock == null) {
                throw new BusinessException("库存记录不存在");
            }

            int decreaseRows = ticketStockMapper.decreaseStock(request.getTicketCategoryId(), request.getQuantity());
            if (decreaseRows == 0) {
                throw new BusinessException("库存不足");
            }

            BigDecimal totalAmount = calculateTotalAmount(ticketCategory.getPrice(), request.getQuantity());
            LocalDateTime now = LocalDateTime.now();

            TicketOrder order = new TicketOrder();
            order.setOrderNo(generateOrderNo());
            order.setUserId(currentUserId);
            order.setShowId(request.getShowId());
            order.setSessionId(request.getSessionId());
            order.setTicketCategoryId(request.getTicketCategoryId());
            order.setQuantity(request.getQuantity());
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

            try {
                orderTimeoutProducer.sendOrderTimeoutMessage(order.getId(), order.getOrderNo());
            } catch (AmqpException exception) {
                LOGGER.error("Failed to send order timeout message, orderId={}", order.getId(), exception);
                throw new BusinessException("订单创建失败，请稍后重试");
            }
            return toOrderVO(order);
        } finally {
            orderSubmitGuard.release(currentUserId, request.getTicketCategoryId());
        }
    }

    private TicketCategory getValidTicketCategory(CreateOrderRequest request) {
        validateShowSessionTicketCategoryRelation(request);
        TicketCategory ticketCategory = ticketCategoryMapper.selectById(request.getTicketCategoryId());
        if (ticketCategory == null) {
            throw new BusinessException(ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        }
        return ticketCategory;
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

        paymentMapper.closeUnpaidByOrderId(orderId, LocalDateTime.now());
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
            return;
        }

        //修改状态为已关闭
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

        //回滚库存
        int rollbackRows = ticketStockMapper.rollbackStock(order.getTicketCategoryId(), order.getQuantity());
        if (rollbackRows != 1) {
            throw new BusinessException("库存回滚失败");
        }

        paymentMapper.closeUnpaidByOrderId(orderId, LocalDateTime.now());
        rollbackRedisStockIfAsyncOrder(order);
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

    private void rollbackRedisStockAfterSubmitFailure(CreateOrderRequest request,
                                                     boolean redisPreDeducted,
                                                     String reason) {
        if (!redisPreDeducted) {
            return;
        }
        rollbackRedisStockAfterSubmitFailure(request.getTicketCategoryId(), request.getQuantity(), reason);
    }

    /**
     * 提交失败的话回滚redis库存
     * @param ticketCategoryId
     * @param quantity
     * @param reason
     */
    private void rollbackRedisStockAfterSubmitFailure(Long ticketCategoryId,
                                                     Integer quantity,
                                                     String reason) {
        try {
            stockLuaService.rollbackStock(ticketCategoryId, quantity);
            LOGGER.info("Rolled back Redis stock after async submit failure, ticketCategoryId={}, quantity={}, reason={}",
                    ticketCategoryId, quantity, reason);
        } catch (RuntimeException rollbackException) {
            LOGGER.error("Failed to rollback Redis stock after async submit failure, ticketCategoryId={}, quantity={}, reason={}",
                    ticketCategoryId, quantity, reason, rollbackException);
        }
    }

    //查限流
    private void checkOrderSubmitRateLimit(Long userId, CreateOrderRequest request) {
        boolean userAllowed = rateLimitService.tryAcquire(
                RedisKeyConstant.rateLimitUserKey(userId, ORDER_SUBMIT_ACTION),
                USER_ORDER_SUBMIT_LIMIT,
                ORDER_SUBMIT_WINDOW_SECONDS
        );

        if (!userAllowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
        }

        boolean ticketAllowed = rateLimitService.tryAcquire(
                RedisKeyConstant.rateLimitTicketKey(request.getTicketCategoryId(), ORDER_SUBMIT_ACTION),
                TICKET_ORDER_SUBMIT_LIMIT,
                ORDER_SUBMIT_WINDOW_SECONDS
        );
        if (!ticketAllowed) {
            throw new BusinessException(ErrorMessageConstant.RATE_LIMITED);
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
