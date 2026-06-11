package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.domain.dto.OrderSnapshot;
import com.zewbby.smartticket.service.StockLuaService;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class AsyncCreateOrderConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCreateOrderConsumer.class);

    private static final String USER_NOT_FOUND = "用户不存在";

    private static final String STOCK_NOT_ENOUGH = "库存不足";

    private final OrderRequestMapper orderRequestMapper;

    private final OrderMapper orderMapper;

    private final UserMapper userMapper;

    private final TicketCategoryMapper ticketCategoryMapper;

    private final TicketStockMapper ticketStockMapper;

    private final TicketStockBucketMapper ticketStockBucketMapper;

    private final OrderTimeoutProducer orderTimeoutProducer;

    private final StockLuaService stockLuaService;

    private final DeadLetterMessageService deadLetterMessageService;

    private final MqConsumerProperties mqConsumerProperties;

    private final ObservabilityMetricsService observabilityMetricsService;

    private final StockBucketProperties stockBucketProperties;

    @Autowired
    public AsyncCreateOrderConsumer(OrderRequestMapper orderRequestMapper,
                                    OrderMapper orderMapper,
                                    UserMapper userMapper,
                                    TicketCategoryMapper ticketCategoryMapper,
                                    TicketStockMapper ticketStockMapper,
                                    TicketStockBucketMapper ticketStockBucketMapper,
                                    OrderTimeoutProducer orderTimeoutProducer,
	                                    StockLuaService stockLuaService,
	                                    DeadLetterMessageService deadLetterMessageService,
	                                    MqConsumerProperties mqConsumerProperties,
	                                    ObservabilityMetricsService observabilityMetricsService,
	                                    StockBucketProperties stockBucketProperties) {
        this.orderRequestMapper = orderRequestMapper;
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.ticketStockBucketMapper = ticketStockBucketMapper;
        this.orderTimeoutProducer = orderTimeoutProducer;
        this.stockLuaService = stockLuaService;
        this.deadLetterMessageService = deadLetterMessageService;
        this.mqConsumerProperties = mqConsumerProperties;
        this.observabilityMetricsService = observabilityMetricsService;
        this.stockBucketProperties = stockBucketProperties;
    }

    public AsyncCreateOrderConsumer(OrderRequestMapper orderRequestMapper,
                                    OrderMapper orderMapper,
                                    UserMapper userMapper,
                                    TicketCategoryMapper ticketCategoryMapper,
                                    TicketStockMapper ticketStockMapper,
                                    OrderTimeoutProducer orderTimeoutProducer,
                                    StockLuaService stockLuaService,
                                    DeadLetterMessageService deadLetterMessageService,
                                    MqConsumerProperties mqConsumerProperties,
                                    ObservabilityMetricsService observabilityMetricsService) {
        this(orderRequestMapper,
                orderMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                null,
                orderTimeoutProducer,
                stockLuaService,
                deadLetterMessageService,
                mqConsumerProperties,
                observabilityMetricsService,
                disabledBucketProperties());
    }

    private static StockBucketProperties disabledBucketProperties() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(false);
        return properties;
    }

    @RabbitListener(queues = "#{orderAsyncQueueNames}", containerFactory = "asyncOrderRabbitListenerContainerFactory")
    @Transactional
    public void consume(AsyncCreateOrderMessage message) {
        LOGGER.info("Received async create order message, requestId={}", message.getRequestId());

        /*
         * RabbitMQ 默认只能保证“至少一次投递”，网络抖动、消费者异常、ACK 丢失都可能导致同一条消息再次投递。
         * Publisher Confirm 只证明 Broker 收到消息，不证明消费者成功处理；可靠投递不等于可靠消费。
         * 所以这里不能相信“我只会消费一次”，也不能只靠内存锁；真正的幂等开关必须落在数据库状态机上。
         * 只有 PRE_DEDUCTED/QUEUED 能通过条件更新进入 PROCESSING，重复消息、成功消息、失败消息都会被挡住。
         */
        TicketOrderRequest orderRequest = claimOrCreateProcessingRequest(message);
        if (orderRequest == null) {
            return;
        }

        try {
            UserAccount user = userMapper.selectById(orderRequest.getUserId());
            if (user == null) {
                LOGGER.warn("Async create order failed, requestId={}, reason={}",
                        orderRequest.getRequestId(), USER_NOT_FOUND);
                markBusinessRejected(message, orderRequest, USER_NOT_FOUND);
                return;
            }

            OrderSnapshot snapshot = getOrderSnapshot(message, orderRequest);
            if (snapshot == null) {
                return;
            }

            /*
             * Redis 预扣只是入口削峰和快速失败，MySQL 才是最终持久化库存。
             * 即使 Redis 已经扣成功，这里也必须用 available_stock >= quantity 的条件更新再扣一次 MySQL，
             * 防止 Redis 重建、预热覆盖、人工修复等场景把缓存和数据库库存搞到不一致后造成超卖。
             */
            boolean bucketStockDecreased = false;
            if (stockBucketProperties.isEnabled() && orderRequest.getStockBucketNo() != null) {
                int bucketRows = orderRequest.getStockBucketVersion() == null
                        ? ticketStockBucketMapper.decreaseStock(
                                orderRequest.getTicketCategoryId(),
                                orderRequest.getStockBucketNo(),
                                orderRequest.getQuantity()
                        )
                        : ticketStockBucketMapper.decreaseStockByVersion(
                                orderRequest.getTicketCategoryId(),
                                orderRequest.getStockBucketVersion(),
                                orderRequest.getStockBucketNo(),
                                orderRequest.getQuantity()
                        );
                if (bucketRows != 1) {
                    LOGGER.warn("Async create order failed on bucket stock, requestId={}, ticketCategoryId={}, bucketVersion={}, bucketNo={}, quantity={}, reason={}",
                            orderRequest.getRequestId(),
                            orderRequest.getTicketCategoryId(),
                            orderRequest.getStockBucketVersion(),
                            orderRequest.getStockBucketNo(),
                            orderRequest.getQuantity(),
                            STOCK_NOT_ENOUGH);
                    markBusinessRejected(message, orderRequest, STOCK_NOT_ENOUGH);
                    return;
                }
                bucketStockDecreased = true;
            }

            if (!bucketStockDecreased) {
                int decreaseRows = ticketStockMapper.decreaseStock(
                        orderRequest.getTicketCategoryId(),
                        orderRequest.getQuantity()
                );
                //减失败就标记状态
                if (decreaseRows != 1) {
                    LOGGER.warn("Async create order failed, requestId={}, ticketCategoryId={}, quantity={}, reason={}",
                            orderRequest.getRequestId(),
                            orderRequest.getTicketCategoryId(),
                            orderRequest.getQuantity(),
                            STOCK_NOT_ENOUGH);
                    markBusinessRejected(message, orderRequest, STOCK_NOT_ENOUGH);
                    return;
                }
            }

            LocalDateTime now = LocalDateTime.now();
            TicketOrder order = new TicketOrder();
            order.setOrderNo(generateOrderNo());
            order.setUserId(orderRequest.getUserId());
            order.setShowId(orderRequest.getShowId());
            order.setSessionId(orderRequest.getSessionId());
            order.setTicketCategoryId(orderRequest.getTicketCategoryId());
            order.setQuantity(orderRequest.getQuantity());
            fillOrderSnapshot(order, snapshot);
            order.setTotalAmount(calculateTotalAmount(snapshot.getTicketPrice(), orderRequest.getQuantity()));
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
                throw new IllegalStateException("订单创建失败");
            }
            observabilityMetricsService.recordOrderCreated();
            LOGGER.info("Created order for async request, requestId={}, orderId={}, orderNo={}",
                    orderRequest.getRequestId(), order.getId(), order.getOrderNo());

            /*
             * 订单创建成功后，超时关闭消息也必须走 Outbox。
             * 如果这里直接发 RabbitMQ，可能出现订单已提交但延迟消息丢失，最终 locked_stock 长期不释放。
             * 写 local_message 后，即使发送器失败，也能通过 Publisher Confirm、重试和人工 retry 找回这条超时消息。
             */
            orderTimeoutProducer.sendOrderTimeoutMessage(buildOrderTimeoutMessage(order));

            //异步请求下单
            int successRows = orderRequestMapper.markSuccess(orderRequest.getId(), order.getId());
            if (successRows != 1) {
                throw new IllegalStateException("异步下单请求状态更新失败");
            }
            observabilityMetricsService.recordAsyncOrderRequestSuccess();
            LOGGER.info("Marked async order request SUCCESS, requestId={}, orderId={}",
                    orderRequest.getRequestId(), order.getId());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to consume async create order message, requestId={}",
                    message.getRequestId(), exception);
            throw new ConsumerRetryableException(
                    ConsumerExceptionTypeEnum.UNKNOWN_ERROR,
                    "异步下单消费者未知异常",
                    exception
            );
        }
    }

    private TicketOrderRequest claimOrCreateProcessingRequest(AsyncCreateOrderMessage message) {
        TicketOrderRequest existingRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (existingRequest == null) {
            return insertProcessingRequestFromMessage(message);
        }
        if (OrderRequestStatusEnum.SUCCESS.getCode().equals(existingRequest.getStatus())) {
            LOGGER.info("Skipped duplicated async order message because request already SUCCESS, requestId={}",
                    existingRequest.getRequestId());
            return null;
        }
        if (OrderRequestStatusEnum.FAILED.getCode().equals(existingRequest.getStatus())
                || OrderRequestStatusEnum.COMPENSATED.getCode().equals(existingRequest.getStatus())
                || OrderRequestStatusEnum.CANCELLED.getCode().equals(existingRequest.getStatus())) {
            LOGGER.info("Skipped async order request with terminal or in-flight status, requestId={}, status={}",
                    existingRequest.getRequestId(), existingRequest.getStatus());
            return null;
        }
        if (OrderRequestStatusEnum.PROCESSING.getCode().equals(existingRequest.getStatus())) {
            handleProcessingRequest(message, existingRequest);
            return null;
        }
        if (!OrderRequestStatusEnum.canEnterProcessing(existingRequest.getStatus())) {
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY,
                    "异步下单请求状态不允许消费: " + existingRequest.getStatus());
            LOGGER.info("Skipped async order request that is not ready for consuming, requestId={}, status={}",
                    existingRequest.getRequestId(), existingRequest.getStatus());
            return null;
        }

        int claimedRows = orderRequestMapper.tryMarkProcessing(message.getRequestId());
        if (claimedRows != 1) {
            LOGGER.info("Skipped async order request because another consumer has claimed it, requestId={}",
                    message.getRequestId());
            return null;
        }

        TicketOrderRequest orderRequest = orderRequestMapper.selectProcessingByRequestId(message.getRequestId());
        if (orderRequest == null) {
            LOGGER.info("Skipped async order request after lock, requestId={} is no longer PROCESSING",
                    message.getRequestId());
        }
        return orderRequest;
    }

    private TicketOrderRequest insertProcessingRequestFromMessage(AsyncCreateOrderMessage message) {
        TicketOrderRequest orderRequest = buildProcessingOrderRequest(message);
        int insertedRows = orderRequestMapper.insertIgnore(orderRequest);
        if (insertedRows == 1) {
            LOGGER.info("Created async order request from message, requestId={}", message.getRequestId());
            return orderRequest;
        }

        TicketOrderRequest existingRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (existingRequest == null) {
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY, "异步下单请求补建失败");
            LOGGER.warn("Recorded dead letter because async order request cannot be created, requestId={}",
                    message.getRequestId());
            return null;
        }
        return claimOrCreateProcessingRequest(message);
    }

    private TicketOrderRequest buildProcessingOrderRequest(AsyncCreateOrderMessage message) {
        LocalDateTime now = LocalDateTime.now();
        TicketOrderRequest orderRequest = new TicketOrderRequest();
        orderRequest.setRequestId(message.getRequestId());
        orderRequest.setUserId(message.getUserId());
        orderRequest.setShowId(message.getShowId());
        orderRequest.setSessionId(message.getSessionId());
        orderRequest.setTicketCategoryId(message.getTicketCategoryId());
        orderRequest.setQuantity(message.getQuantity());
        orderRequest.setStatus(OrderRequestStatusEnum.PROCESSING.getCode());
        orderRequest.setOrderId(null);
        orderRequest.setStockBucketVersion(message.getStockBucketVersion());
        orderRequest.setStockBucketNo(message.getStockBucketNo());
        orderRequest.setProcessingAt(now);
        orderRequest.setRedisDeducted(message.getRedisDeducted() == null || message.getRedisDeducted());
        orderRequest.setDeductedQuantity(message.getDeductedQuantity() == null ? message.getQuantity() : message.getDeductedQuantity());
        orderRequest.setDeductedAt(message.getDeductedAt() == null ? now : message.getDeductedAt());
        orderRequest.setCompensated(false);
        orderRequest.setCompensationStatus("NONE");
        orderRequest.setCompensatedAt(null);
        orderRequest.setFailReason(null);
        orderRequest.setMessageId(message.getMessageId());
        orderRequest.setCreatedAt(now);
        orderRequest.setUpdatedAt(now);
        return orderRequest;
    }

    private OrderTimeoutMessage buildOrderTimeoutMessage(TicketOrder order) {
        OrderTimeoutMessage message = new OrderTimeoutMessage();
        message.setOrderId(order.getId());
        message.setOrderNo(order.getOrderNo());
        message.setUserId(order.getUserId());
        message.setExpireTime(order.getExpireTime());
        /*
         * 异步创单线程和后续超时关闭消费者不是同一个调用栈。
         * 当前项目没有完整 TraceContext，先用订单维度的稳定 traceId 把 local_message、RabbitMQ 投递和超时关闭日志串起来。
         */
        message.setTraceId("order-timeout-" + order.getId());
        message.setMessageId(null);
        return message;
    }

    private void handleProcessingRequest(AsyncCreateOrderMessage message, TicketOrderRequest existingRequest) {
        LocalDateTime timeoutBefore = LocalDateTime.now().minusSeconds(mqConsumerProperties.getProcessingTimeoutSeconds());
        if (existingRequest.getProcessingAt() != null && existingRequest.getProcessingAt().isAfter(timeoutBefore)) {
            LOGGER.info("Skipped duplicated async order message because request is still PROCESSING, requestId={}",
                    existingRequest.getRequestId());
            return;
        }

        /*
         * PROCESSING 代表某个消费者已经抢到处理权。正常情况下它很短暂；如果卡住超过阈值，
         * 通常说明消费者进程崩溃、事务悬挂或历史版本没有正确回滚。这里把它显式转 FAILED 并落死信，
         * 避免请求永久停在 PROCESSING，用户查不到结果，库存也没人补偿。
         */
        String reason = "异步下单请求PROCESSING超时，已进入人工处理";
        int rows = orderRequestMapper.markProcessingTimeout(existingRequest.getId(), reason);
        if (rows == 1) {
            observabilityMetricsService.recordAsyncOrderRequestFailed();
            TicketOrderRequest failedRequest = orderRequestMapper.selectByRequestId(existingRequest.getRequestId());
            compensateRedisPreDeductedStock(failedRequest, reason);
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY, reason);
        }
    }

    private void markBusinessRejected(AsyncCreateOrderMessage message,
                                      TicketOrderRequest orderRequest,
                                      String failReason) {
        /*
         * 业务失败和系统失败要分开处理：库存不足、关系校验失败、用户不存在通常重试也不会变好，
         * 继续让 RabbitMQ 重试只会刷日志、拖慢队列。所以这里直接更新 request 失败并做 Redis 补偿，
         * 同时落 dead_letter_message，便于后续人工判断是否忽略或修正数据后重试。
         */
        markFailedAndCompensateRedis(orderRequest, failReason);
        recordDeadLetter(message, ConsumerExceptionTypeEnum.BUSINESS_REJECT, failReason);
    }

    private boolean markFailed(TicketOrderRequest orderRequest, String failReason) {
        int failedRows = orderRequestMapper.markFailed(orderRequest.getId(), failReason);
        if (failedRows != 1) {
            LOGGER.warn("Skipped marking async order request FAILED, requestId={}, failReason={}",
                    orderRequest.getRequestId(), failReason);
            return false;
        }
        LOGGER.info("Marked async order request FAILED, requestId={}, reason={}",
                orderRequest.getRequestId(), failReason);
        observabilityMetricsService.recordAsyncOrderRequestFailed();
        return true;
    }

    private void markFailedAndCompensateRedis(TicketOrderRequest orderRequest, String failReason) {
        if (!markFailed(orderRequest, failReason)) {
            return;
        }
        compensateRedisPreDeductedStock(orderRequest, failReason);
    }

    private void compensateRedisPreDeductedStock(TicketOrderRequest orderRequest, String failReason) {
        if (orderRequest == null) {
            return;
        }
        if (!Boolean.TRUE.equals(orderRequest.getRedisDeducted())) {
            LOGGER.info("Skipped Redis pre-deduct release because request has no deducted marker, requestId={}",
                    orderRequest.getRequestId());
            return;
        }
        if (orderRequest.getDeductedQuantity() == null || orderRequest.getDeductedQuantity() <= 0) {
            int claimRows = orderRequestMapper.tryMarkCompensating(orderRequest.getId());
            if (claimRows == 1) {
                orderRequestMapper.markCompensateFailed(orderRequest.getId(), "Redis已预扣但deducted_quantity缺失");
            }
            recordDeadLetter(
                    new AsyncCreateOrderMessage(
                            orderRequest.getRequestId(),
                            orderRequest.getUserId(),
                            orderRequest.getShowId(),
                            orderRequest.getSessionId(),
                            orderRequest.getTicketCategoryId(),
                            orderRequest.getQuantity()
                    ),
                    ConsumerExceptionTypeEnum.DATA_INCONSISTENCY,
                    "Redis已预扣但deducted_quantity缺失"
            );
            return;
        }
        /*
         * compensated 这个 boolean 只能表达“是否补偿完成”，无法表达“正在补偿”或“补偿失败”。
         * 因此本阶段增加 compensation_status，用条件更新把 FAILED + NONE 抢成 COMPENSATING。
         * 抢占失败说明另一个线程或人工流程已经在补偿，当前消费者不能重复 INCR Redis 库存。
         */
        int claimRows = orderRequestMapper.tryMarkCompensating(orderRequest.getId());
        if (claimRows != 1) {
            LOGGER.info("Skipped Redis compensation because another process has claimed it, requestId={}",
                    orderRequest.getRequestId());
            return;
        }
        try {
            RedisStockReleaseResult releaseResult = stockLuaService.releasePreDeductedStock(
                    orderRequest.getRequestId(),
                    orderRequest.getTicketCategoryId(),
                    orderRequest.getStockBucketVersion(),
                    orderRequest.getStockBucketNo(),
                    orderRequest.getDeductedQuantity()
            );
            if (releaseResult.isSuccess() || releaseResult == RedisStockReleaseResult.ALREADY_COMPENSATED) {
                orderRequestMapper.markCompensated(orderRequest.getId(), LocalDateTime.now());
            } else {
                orderRequestMapper.markCompensateFailed(orderRequest.getId(), failReason + ", Redis补偿失败: " + releaseResult.getMessage());
            }
            LOGGER.info("Released Redis pre-deducted stock for failed async request, requestId={}, ticketCategoryId={}, quantity={}, result={}",
                    orderRequest.getRequestId(),
                    orderRequest.getTicketCategoryId(),
                    orderRequest.getDeductedQuantity(),
                    releaseResult);
        } catch (RuntimeException exception) {
            /*
             * Redis 释放失败时不能假装成功，否则库存会被长期占住且排查不到。
             * 这里把 compensation_status 标记为 COMPENSATE_FAILED，既兼容旧的 compensated=false，
             * 又能让后续 Task D+ 巡检知道这是“补偿失败待处理”，而不是“还没开始补偿”。
             */
            orderRequestMapper.markCompensateFailed(orderRequest.getId(), failReason + ", Redis补偿异常: " + exception.getMessage());
            LOGGER.error("Failed to release Redis pre-deducted stock for failed async request, requestId={}, keep FAILED for later compensation",
                    orderRequest.getRequestId(), exception.getMessage());
        }
    }

    private OrderSnapshot getOrderSnapshot(AsyncCreateOrderMessage message, TicketOrderRequest orderRequest) {
        boolean relationExists = ticketCategoryMapper.existsShowSessionTicketCategoryRelation(
                orderRequest.getShowId(),
                orderRequest.getSessionId(),
                orderRequest.getTicketCategoryId()
        );
        if (!relationExists) {
            LOGGER.warn("Async create order failed, requestId={}, reason={}",
                    orderRequest.getRequestId(), ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);
            markBusinessRejected(message, orderRequest, ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);
            return null;
        }

        OrderSnapshot snapshot = ticketCategoryMapper.selectOrderSnapshot(
                orderRequest.getShowId(),
                orderRequest.getSessionId(),
                orderRequest.getTicketCategoryId()
        );
        if (snapshot == null) {
            LOGGER.warn("Async create order failed, requestId={}, reason={}",
                    orderRequest.getRequestId(), ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
            markBusinessRejected(message, orderRequest, ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        }
        return snapshot;
    }

    private void fillOrderSnapshot(TicketOrder order, OrderSnapshot snapshot) {
        /*
         * 异步消费者创建订单时也必须保存快照。
         * 否则高并发主链路生成的订单没有历史名称和下单价格，后台改价后就无法解释用户当时到底买了什么。
         */
        order.setShowTitle(snapshot.getShowTitle());
        order.setSessionStartTime(snapshot.getSessionStartTime());
        order.setTicketCategoryName(snapshot.getTicketCategoryName());
        order.setTicketPrice(snapshot.getTicketPrice());
    }

    private void recordDeadLetter(AsyncCreateOrderMessage message,
                                  ConsumerExceptionTypeEnum exceptionType,
                                  String reason) {
        deadLetterMessageService.recordAsyncCreateOrderDeadLetter(
                message,
                RabbitMqConstant.ORDER_ASYNC_QUEUE,
                RabbitMqConstant.ORDER_ASYNC_EXCHANGE,
                RabbitMqConstant.ORDER_ASYNC_ROUTING_KEY,
                null,
                exceptionType,
                reason
        );
    }

    /**
     * 算总金额
     * @param price
     * @param quantity
     * @return
     */
    private BigDecimal calculateTotalAmount(BigDecimal price, Integer quantity) {
        if (price == null) {
            throw new IllegalStateException("票档价格不存在");
        }
        return price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 生成订单号
     * @return
     */
    private String generateOrderNo() {
        return "ST" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }
}
