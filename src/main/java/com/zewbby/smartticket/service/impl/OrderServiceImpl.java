package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.cache.OrderSubmitGuard;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.RabbitMqConstant;
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
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.AsyncOrderProducer;
import com.zewbby.smartticket.mq.OrderTimeoutProducer;
import com.zewbby.smartticket.ratelimit.RateLimitService;
import com.zewbby.smartticket.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
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

    private final UserMapper userMapper;

    private final TicketCategoryMapper ticketCategoryMapper;

    private final TicketStockMapper ticketStockMapper;

    private final OrderSubmitGuard orderSubmitGuard;

    private final OrderTimeoutProducer orderTimeoutProducer;

    private final AsyncOrderProducer asyncOrderProducer;

    private final RateLimitService rateLimitService;

    private final IdempotencyTokenService idempotencyTokenService;

    public OrderServiceImpl(OrderMapper orderMapper,
                            OrderRequestMapper orderRequestMapper,
                            UserMapper userMapper,
                            TicketCategoryMapper ticketCategoryMapper,
                            TicketStockMapper ticketStockMapper,
                            OrderSubmitGuard orderSubmitGuard,
                            OrderTimeoutProducer orderTimeoutProducer,
                            AsyncOrderProducer asyncOrderProducer,
                            RateLimitService rateLimitService,
                            IdempotencyTokenService idempotencyTokenService) {
        this.orderMapper = orderMapper;
        this.orderRequestMapper = orderRequestMapper;
        this.userMapper = userMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.orderSubmitGuard = orderSubmitGuard;
        this.orderTimeoutProducer = orderTimeoutProducer;
        this.asyncOrderProducer = asyncOrderProducer;
        this.rateLimitService = rateLimitService;
        this.idempotencyTokenService = idempotencyTokenService;
    }

    /**
     * 提交异步订单
     * @param request
     * @return
     */
    @Override
    @Transactional(noRollbackFor = BusinessException.class)
    public OrderRequestVO submitAsyncOrder(CreateOrderRequest request) {
        checkOrderSubmitRateLimit(request);

        if (!orderSubmitGuard.tryAcquire(request.getUserId(), request.getTicketCategoryId())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_SUBMIT);
        }
        idempotencyTokenService.consumeOrderToken(request.getUserId(), request.getIdempotencyToken());

        TicketOrderRequest orderRequest = null;
        try {
            UserAccount user = userMapper.selectById(request.getUserId());
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            TicketCategory ticketCategory = ticketCategoryMapper.selectById(request.getTicketCategoryId());
            if (ticketCategory == null) {
                throw new BusinessException("票档不存在");
            }

            LocalDateTime now = LocalDateTime.now();
            String requestId = generateRequestId();

            orderRequest = new TicketOrderRequest();
            orderRequest.setRequestId(requestId);
            orderRequest.setUserId(request.getUserId());
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
                    request.getUserId(),
                    request.getShowId(),
                    request.getSessionId(),
                    request.getTicketCategoryId(),
                    request.getQuantity()
            );
            //生产者发一下创建订单的消息
            asyncOrderProducer.sendAsyncCreateOrderMessage(message);

            return toOrderRequestVO(orderRequest);
        } catch (AmqpException exception) {
            //捕获消息队列（MQ）发送失败的异常。
            if (orderRequest != null && orderRequest.getId() != null) {
                orderRequestMapper.markFailed(orderRequest.getId(), "MQ发送失败");
            }
            LOGGER.error("Failed to send async create order message, requestId={}",
                    orderRequest == null ? null : orderRequest.getRequestId(), exception);
            orderSubmitGuard.release(request.getUserId(), request.getTicketCategoryId());
            throw new BusinessException("异步下单请求提交失败，请稍后重试");
        } catch (RuntimeException exception) {
            //发生其他意外就释放锁
            orderSubmitGuard.release(request.getUserId(), request.getTicketCategoryId());
            throw exception;
        }
    }

    @Override
    public OrderRequestVO getOrderRequestResult(String requestId) {
        TicketOrderRequest orderRequest = orderRequestMapper.selectByRequestId(requestId);
        if (orderRequest == null) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REQUEST_NOT_FOUND);
        }
        return toOrderRequestVO(orderRequest);
    }

    @Override
    @Transactional
    public OrderVO createOrder(CreateOrderRequest request) {
        //限流
        checkOrderSubmitRateLimit(request);

        //防重复提交
        if (!orderSubmitGuard.tryAcquire(request.getUserId(), request.getTicketCategoryId())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_SUBMIT);
        }
        idempotencyTokenService.consumeOrderToken(request.getUserId(), request.getIdempotencyToken());

        try {
            UserAccount user = userMapper.selectById(request.getUserId());
            if (user == null) {
                throw new BusinessException("用户不存在");
            }

            TicketCategory ticketCategory = ticketCategoryMapper.selectById(request.getTicketCategoryId());
            if (ticketCategory == null) {
                throw new BusinessException("票档不存在");
            }

            TicketStock ticketStock = ticketStockMapper.selectByTicketCategoryId(request.getTicketCategoryId());
            if (ticketStock == null) {
                throw new BusinessException("库存记录不存在");
            }

            int decreaseRows = ticketStockMapper.decreaseStock(request.getTicketCategoryId(), request.getQuantity());
            if (decreaseRows == 0) {
                throw new BusinessException("库存不足");
            }

            Integer totalAmount = calculateTotalAmount(ticketCategory.getPrice(), request.getQuantity());
            LocalDateTime now = LocalDateTime.now();

            TicketOrder order = new TicketOrder();
            order.setOrderNo(generateOrderNo());
            order.setUserId(request.getUserId());
            order.setShowId(request.getShowId());
            order.setSessionId(request.getSessionId());
            order.setTicketCategoryId(request.getTicketCategoryId());
            order.setQuantity(request.getQuantity());
            order.setTotalAmount(totalAmount);
            order.setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode());
            order.setExpireTime(now.plus(Duration.ofMillis(RabbitMqConstant.ORDER_TIMEOUT_TTL_MILLIS)));
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
            orderSubmitGuard.release(request.getUserId(), request.getTicketCategoryId());
        }
    }

    @Override
    public OrderVO getOrderById(Long orderId) {
        return toOrderVO(getExistingOrder(orderId));
    }

    @Override
    public List<OrderVO> listUserOrders(Long userId) {
        return orderMapper.selectByUserId(userId)
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
        TicketOrder order = getExistingOrder(orderId);
        if (OrderStatusEnum.isCancelled(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_CANCEL);
        }
        if (!OrderStatusEnum.isPendingPayment(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        int updateRows = orderMapper.updateCancelStatus(
                orderId,
                OrderStatusEnum.PENDING_PAYMENT.getCode(),
                OrderStatusEnum.CANCELLED.getCode(),
                LocalDateTime.now(),
                USER_CANCEL_REASON
        );
        if (updateRows != 1) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        int rollbackRows = ticketStockMapper.rollbackStock(order.getTicketCategoryId(), order.getQuantity());
        if (rollbackRows != 1) {
            throw new BusinessException("库存回滚失败");
        }

        return toOrderVO(orderMapper.selectById(orderId));
    }

    /**
     * 支付订单
     * @param orderId
     * @return
     */
    @Override
    @Transactional
    public OrderVO payOrder(Long orderId) {
        TicketOrder order = getExistingOrder(orderId);
        //检查是否支付过了
        if (OrderStatusEnum.isPaid(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_REPEAT_PAY);
        }
        //检查是否为待支付
        if (!OrderStatusEnum.isPendingPayment(order.getStatus())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }
        //检查是否过期
        if (order.getExpireTime() != null && LocalDateTime.now().isAfter(order.getExpireTime())) {
            throw new BusinessException(ErrorMessageConstant.ORDER_EXPIRED);
        }

        LocalDateTime payTime = LocalDateTime.now();
        int updateRows = orderMapper.updatePayStatus(
                orderId,
                OrderStatusEnum.PENDING_PAYMENT.getCode(),
                OrderStatusEnum.PAID.getCode(),
                payTime
        );
        //修改行数不是1，也就是没做任何更改(已经被其他请求更改过了)
        if (updateRows != 1) {
            throw new BusinessException(ErrorMessageConstant.ORDER_STATUS_NOT_ALLOWED);
        }

        //扣库存
        int confirmRows = ticketStockMapper.confirmStock(order.getTicketCategoryId(), order.getQuantity());
        if (confirmRows != 1) {
            throw new BusinessException("库存确认失败");
        }

        return toOrderVO(orderMapper.selectById(orderId));
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
    }

    /**
     * 算总金额
     * @param price
     * @param quantity
     * @return
     */
    private Integer calculateTotalAmount(BigDecimal price, Integer quantity) {
        if (price == null) {
            throw new BusinessException("票档价格不存在");
        }
        return price.multiply(BigDecimal.valueOf(quantity)).intValue();
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

    //查限流
    private void checkOrderSubmitRateLimit(CreateOrderRequest request) {
        boolean userAllowed = rateLimitService.tryAcquire(
                RedisKeyConstant.rateLimitUserKey(request.getUserId(), ORDER_SUBMIT_ACTION),
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

    /**
     * 检查订单是否存在
     * @param orderId
     * @return
     */
    private TicketOrder getExistingOrder(Long orderId) {
        TicketOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
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
