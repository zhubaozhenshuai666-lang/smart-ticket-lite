package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.domain.dto.ActivityScope;
import com.zewbby.smartticket.domain.dto.OrderSnapshot;
import com.zewbby.smartticket.service.StockLuaService;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.domain.vo.OrderRequestVO;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.enums.UserStatusEnum;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockBucketMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.service.AsyncOrderInFlightService;
import com.zewbby.smartticket.service.AsyncOrderRequestResultCacheService;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import com.zewbby.smartticket.service.DomainEventPublisher;
import com.zewbby.smartticket.service.ObservabilityMetricsService;
import com.zewbby.smartticket.service.OrderSnapshotCacheService;
import com.zewbby.smartticket.service.ShowRelationCacheService;
import com.zewbby.smartticket.service.UserStatusCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final UserStatusCacheService userStatusCacheService;

    private final ShowRelationCacheService showRelationCacheService;

    private final OrderSnapshotCacheService orderSnapshotCacheService;

    private final AsyncOrderInFlightService asyncOrderInFlightService;

    @Autowired(required = false)
    private AsyncOrderSubmitProperties asyncOrderSubmitProperties;

    @Autowired(required = false)
    private AsyncOrderRequestResultCacheService asyncOrderRequestResultCacheService;

    @Autowired(required = false)
    private DomainEventPublisher domainEventPublisher;

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
                                        StockBucketProperties stockBucketProperties,
                                        UserStatusCacheService userStatusCacheService,
                                        ShowRelationCacheService showRelationCacheService,
                                        OrderSnapshotCacheService orderSnapshotCacheService,
                                        AsyncOrderInFlightService asyncOrderInFlightService) {
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
        this.userStatusCacheService = userStatusCacheService;
        this.showRelationCacheService = showRelationCacheService;
        this.orderSnapshotCacheService = orderSnapshotCacheService;
        this.asyncOrderInFlightService = asyncOrderInFlightService;
    }

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
                                    StockBucketProperties stockBucketProperties,
                                    UserStatusCacheService userStatusCacheService,
                                    ShowRelationCacheService showRelationCacheService) {
        this(orderRequestMapper,
                orderMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                ticketStockBucketMapper,
                orderTimeoutProducer,
                stockLuaService,
                deadLetterMessageService,
                mqConsumerProperties,
                observabilityMetricsService,
                stockBucketProperties,
                userStatusCacheService,
                showRelationCacheService,
                null,
                null);
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
                disabledBucketProperties(),
                null,
                null);
    }

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
        this(orderRequestMapper,
                orderMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                ticketStockBucketMapper,
                orderTimeoutProducer,
                stockLuaService,
                deadLetterMessageService,
                mqConsumerProperties,
                observabilityMetricsService,
                stockBucketProperties,
                null,
                null);
    }

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
                                    StockBucketProperties stockBucketProperties,
                                    UserStatusCacheService userStatusCacheService) {
        this(orderRequestMapper,
                orderMapper,
                userMapper,
                ticketCategoryMapper,
                ticketStockMapper,
                ticketStockBucketMapper,
                orderTimeoutProducer,
                stockLuaService,
                deadLetterMessageService,
                mqConsumerProperties,
                observabilityMetricsService,
                stockBucketProperties,
                userStatusCacheService,
                null);
    }

    private static StockBucketProperties disabledBucketProperties() {
        StockBucketProperties properties = new StockBucketProperties();
        properties.setEnabled(false);
        return properties;
    }

    /**
     * 主干链路！
     * MQ消费的唯一入口。接收请求 -> 防重校验 -> 扣减MySQL库存 -> 生成订单 -> 发送延时关单消息 -> 释放流控。
     * 将前端积压的异步流量，平滑且安全地转化为真实的数据库订单。
     * @param message
     */
    @Transactional
    public void consume(AsyncCreateOrderMessage message) {
        LOGGER.info("Received async create order message, requestId={}", message.getRequestId());

        //检验message的状态是否合法，抛弃重复,超时消息。不重复加乐观锁后返回对应实体
        TicketOrderRequest orderRequest = claimOrCreateProcessingRequest(message);
        if (orderRequest == null) {
            return;
        }

        try {
            //校验用户的合法性
            if (!isNormalUser(orderRequest.getUserId())) {
                LOGGER.warn("Async create order failed, requestId={}, reason={}",
                        orderRequest.getRequestId(), USER_NOT_FOUND);
                markBusinessRejected(message, orderRequest, USER_NOT_FOUND);
                return;
            }

            //取快照
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
            //：全局开分桶且上游传下来的请求明确指定了要扣减哪一个桶 进入逻辑
            if (stockBucketProperties.isEnabled() && orderRequest.getStockBucketNo() != null) {
                // 根据有没有分桶version来走不同的扣减逻辑
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
                //如果如果库存不够扣失败的话返回扣除失败
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

            //桶预扣失败的话就走常规的非桶
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

            //订单落库
            int insertRows = orderMapper.insert(order);
            //防重复插入
            if (insertRows != 1) {
                throw new IllegalStateException("订单创建失败");
            }
            //非功能性需求（NFR）中的可观测性（Observability）建设，其直接作用对象并非普通用户或数据库，而是运维监控体系
            observabilityMetricsService.recordOrderCreated();
            publishOrderCreatedEvents(order);
            LOGGER.info("Created order for async request, requestId={}, orderId={}, orderNo={}",
                    orderRequest.getRequestId(), order.getId(), order.getOrderNo());

            /*
             * 订单创建成功后，超时关闭消息必须走统一生产器。
             * Kafka 没有原生延时队列语义，延时关闭主要依赖扫描兜底；开启超时事件时也必须由消费者重新校验 expireTime。
             */
            orderTimeoutProducer.sendOrderTimeoutMessage(buildOrderTimeoutMessage(order));

            //业务完成后将状态标记为成功
            int successRows = orderRequestMapper.markSuccess(orderRequest.getId(), order.getId());
            //已标记过的话就直接弹错
            if (successRows != 1) {
                throw new IllegalStateException("异步下单请求状态更新失败");
            }
            orderRequest.setStatus(OrderRequestStatusEnum.SUCCESS.getCode());
            orderRequest.setOrderId(order.getId());
            cacheAsyncOrderResult(orderRequest);
            //异步订单处理结束（无论成功还是失败）后，释放该票档占用的In-Flight并发名额，让前端正在排队的其他用户可以进入系统。
            releaseAsyncOrderInFlight(orderRequest);
            //非业务需求
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

    /**
     * 抢占当前消息的处理权，过滤掉无效、重复的消息。
     * 在架构中的作用：实现消费端绝对幂等的核心防线。因为 MQ 会重复发消息，这里必须确保一笔订单请求只被处理一次。
     * @param message
     * @return
     */
    private TicketOrderRequest claimOrCreateProcessingRequest(AsyncCreateOrderMessage message) {
        if (isFastPipelineRequestRebuildEnabled()) {
            TicketOrderRequest insertedRequest = tryInsertProcessingRequestFromMessage(message);
            if (insertedRequest != null) {
                return insertedRequest;
            }
        }

        //查数据库现在的状态。
        TicketOrderRequest existingRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (existingRequest == null) {
            return insertProcessingRequestFromMessage(message);
        }
        //如果是 SUCCESS / FAILED / CANCELLED，说明已经处理过了，直接丢弃（return null）。
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
        //如果是 PROCESSING，说明别人正在处理，或者别人处理一半死机了，转交给 handleProcessingRequest 判断是否超时。
        if (OrderRequestStatusEnum.PROCESSING.getCode().equals(existingRequest.getStatus())) {
            handleProcessingRequest(message, existingRequest);
            return null;
        }
        // 状态合法性检查，防止因脏数据导致的逻辑错误，并将异常请求拦截在门外
        if (!OrderRequestStatusEnum.canEnterProcessing(existingRequest.getStatus())) {
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY,
                    "异步下单请求状态不允许消费: " + existingRequest.getStatus());
            LOGGER.info("Skipped async order request that is not ready for consuming, requestId={}, status={}",
                    existingRequest.getRequestId(), existingRequest.getStatus());
            return null;
        }

        //只有更新影响行数为 1 的线程，才真正拿到了处理权。
        int claimedRows = orderRequestMapper.tryMarkProcessing(message.getRequestId());
        if (claimedRows != 1) {
            LOGGER.info("Skipped async order request because another consumer has claimed it, requestId={}",
                    message.getRequestId());
            return null;
        }

        //创建对应的TickertOrderRequest
        TicketOrderRequest orderRequest = orderRequestMapper.selectProcessingByRequestId(message.getRequestId());
        if (orderRequest == null) {
            LOGGER.info("Skipped async order request after lock, requestId={} is no longer PROCESSING",
                    message.getRequestId());
        }
        return orderRequest;
    }

    private boolean isFastPipelineRequestRebuildEnabled() {
        return asyncOrderSubmitProperties != null
                && !asyncOrderSubmitProperties.isPersistRequestBeforePublish();
    }

    private TicketOrderRequest tryInsertProcessingRequestFromMessage(AsyncCreateOrderMessage message) {
        TicketOrderRequest orderRequest = buildProcessingOrderRequest(message);
        int insertedRows = orderRequestMapper.insertIgnore(orderRequest);
        if (insertedRows == 1) {
            LOGGER.info("Created async order request from message, requestId={}", message.getRequestId());
            return orderRequest;
        }
        return null;
    }

    private TicketOrderRequest insertProcessingRequestFromMessage(AsyncCreateOrderMessage message) {
        TicketOrderRequest insertedRequest = tryInsertProcessingRequestFromMessage(message);
        if (insertedRequest != null) {
            return insertedRequest;
        }

        TicketOrderRequest existingRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (existingRequest == null) {
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY, "异步下单请求补建失败");
            releaseAsyncOrderInFlight(message.getTicketCategoryId());
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

    private boolean isNormalUser(Long userId) {
        if (userStatusCacheService != null) {
            return userStatusCacheService.isNormalUser(userId);
        }
        UserAccount user = userMapper.selectById(userId);
        return user != null && UserStatusEnum.isNormal(user.getStatus());
    }

    private OrderTimeoutMessage buildOrderTimeoutMessage(TicketOrder order) {
        OrderTimeoutMessage message = new OrderTimeoutMessage();
        message.setOrderId(order.getId());
        message.setOrderNo(order.getOrderNo());
        message.setUserId(order.getUserId());
        message.setExpireTime(order.getExpireTime());
        /*
         * 异步创单线程和后续超时关闭消费者不是同一个调用栈。
         * 当前项目没有完整 TraceContext，先用订单维度的稳定 traceId 把 local_message、Kafka 投递和超时关闭日志串起来。
         */
        message.setTraceId("order-timeout-" + order.getId());
        message.setMessageId(null);
        return message;
    }

    private void publishOrderCreatedEvents(TicketOrder order) {
        if (domainEventPublisher == null || order == null) {
            return;
        }
        domainEventPublisher.publishOrderCreated(order);
        domainEventPublisher.publishStockChanged(
                order.getTicketCategoryId(),
                order.getId(),
                "ORDER_CREATED_LOCKED",
                order.getQuantity()
        );
    }

    /**
     * 清理这些死在半路、状态卡在 PROCESSING（处理中）的订单请求，并把被它们扣掉的库存和流控名额夺回来。
     * 分布式事务防悬挂（Anti-Hanging）设计。
     * @param message
     * @param existingRequest
     */
    private void handleProcessingRequest(AsyncCreateOrderMessage message, TicketOrderRequest existingRequest) {
        LocalDateTime timeoutBefore = LocalDateTime.now().minusSeconds(mqConsumerProperties.getProcessingTimeoutSeconds());
        //没超时就放过
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
            releaseAsyncOrderInFlight(existingRequest);
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY, reason);
        }
    }

    private void markBusinessRejected(AsyncCreateOrderMessage message,
                                      TicketOrderRequest orderRequest,
                                      String failReason) {
        /*
         * 业务失败和系统失败要分开处理：库存不足、关系校验失败、用户不存在通常重试也不会变好，
         * 继续让 Kafka 重试只会刷日志、拖慢消费。所以这里直接更新 request 失败并做 Redis 补偿，
         * 同时落 dead_letter_message，便于后续人工判断是否忽略或修正数据后重试。
         */
        if (markFailedAndCompensateRedis(orderRequest, failReason)) {
            releaseAsyncOrderInFlight(orderRequest);
        }
        recordDeadLetter(message, ConsumerExceptionTypeEnum.BUSINESS_REJECT, failReason);
    }

    /**
     * 标记失败
     * @param orderRequest
     * @param failReason
     * @return
     */
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

    /**
     * 失败后补偿
     * @param orderRequest
     * @param failReason
     * @return
     */
    private boolean markFailedAndCompensateRedis(TicketOrderRequest orderRequest, String failReason) {
        if (!markFailed(orderRequest, failReason)) {
            return false;
        }
        compensateRedisPreDeductedStock(orderRequest, failReason);
        orderRequest.setStatus(OrderRequestStatusEnum.FAILED.getCode());
        orderRequest.setFailReason(failReason);
        cacheAsyncOrderResult(orderRequest);
        return true;
    }

    private void cacheAsyncOrderResult(TicketOrderRequest orderRequest) {
        if (asyncOrderRequestResultCacheService == null || orderRequest == null) {
            return;
        }
        asyncOrderRequestResultCacheService.cacheTerminalResult(orderRequest.getUserId(), toOrderRequestVO(orderRequest));
    }

    private OrderRequestVO toOrderRequestVO(TicketOrderRequest orderRequest) {
        OrderRequestVO orderRequestVO = new OrderRequestVO();
        orderRequestVO.setRequestId(orderRequest.getRequestId());
        orderRequestVO.setStatus(orderRequest.getStatus());
        orderRequestVO.setOrderId(orderRequest.getOrderId());
        orderRequestVO.setProcessingAt(orderRequest.getProcessingAt());
        orderRequestVO.setRedisDeducted(orderRequest.getRedisDeducted());
        orderRequestVO.setDeductedQuantity(orderRequest.getDeductedQuantity());
        orderRequestVO.setStockBucketVersion(orderRequest.getStockBucketVersion());
        orderRequestVO.setStockBucketNo(orderRequest.getStockBucketNo());
        orderRequestVO.setDeductedAt(orderRequest.getDeductedAt());
        orderRequestVO.setCompensated(orderRequest.getCompensated());
        orderRequestVO.setCompensationStatus(orderRequest.getCompensationStatus());
        orderRequestVO.setCompensatedAt(orderRequest.getCompensatedAt());
        orderRequestVO.setFailReason(orderRequest.getFailReason());
        orderRequestVO.setMessageId(orderRequest.getMessageId());
        orderRequestVO.setCreatedAt(orderRequest.getCreatedAt());
        orderRequestVO.setUpdatedAt(orderRequest.getUpdatedAt());
        return orderRequestVO;
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
             */
            orderRequestMapper.markCompensateFailed(orderRequest.getId(), failReason + ", Redis补偿异常: " + exception.getMessage());
            LOGGER.error("Failed to release Redis pre-deducted stock for failed async request, requestId={}, keep FAILED for later compensation",
                    orderRequest.getRequestId(), exception.getMessage());
        }
    }

    /**
     * 获取票档的快照信息
     * 交易防篡改
     * @param message
     * @param orderRequest
     * @return
     */
    private OrderSnapshot getOrderSnapshot(AsyncCreateOrderMessage message, TicketOrderRequest orderRequest) {
        OrderSnapshot snapshot = selectOrderSnapshot(orderRequest);
        if (snapshot != null) {
            return snapshot;
        }

        /*
         * selectOrderSnapshot 使用的 published 三表关联条件已经覆盖 show/session/ticketCategory 的合法关系。
         * 成功路径直接使用快照结果，避免消费者每单都先查关系再查快照。只有快照缺失时才补一次关系判断，
         * 用来区分“关系非法”和“关系存在但快照数据异常”的失败原因。
         */
        boolean relationExists = existsPublishedRelation(orderRequest);
        if (!relationExists) {
            LOGGER.warn("Async create order failed, requestId={}, reason={}",
                    orderRequest.getRequestId(), ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);
            markBusinessRejected(message, orderRequest, ErrorMessageConstant.SHOW_SESSION_TICKET_CATEGORY_NOT_MATCH);
            return null;
        }

        LOGGER.warn("Async create order failed, requestId={}, reason={}",
                orderRequest.getRequestId(), ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        markBusinessRejected(message, orderRequest, ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        return null;
    }

    /**
     * 查快照
     * @param orderRequest
     * @return
     */
    private OrderSnapshot selectOrderSnapshot(TicketOrderRequest orderRequest) {
        //快照cache里有的话就直接返回
        if (orderSnapshotCacheService != null) {
            return orderSnapshotCacheService.getPublishedSnapshot(
                    orderRequest.getShowId(),
                    orderRequest.getSessionId(),
                    orderRequest.getTicketCategoryId()
            );
        }
        //否则就查数据库再返回
        return ticketCategoryMapper.selectOrderSnapshot(
                orderRequest.getShowId(),
                orderRequest.getSessionId(),
                orderRequest.getTicketCategoryId()
        );
    }

    private boolean existsPublishedRelation(TicketOrderRequest orderRequest) {
        if (showRelationCacheService != null) {
            return showRelationCacheService.existsPublishedRelation(
                    orderRequest.getShowId(),
                    orderRequest.getSessionId(),
                    orderRequest.getTicketCategoryId()
            );
        }
        return ticketCategoryMapper.existsShowSessionTicketCategoryRelation(
                orderRequest.getShowId(),
                orderRequest.getSessionId(),
                orderRequest.getTicketCategoryId()
        );
    }

    private void releaseAsyncOrderInFlight(TicketOrderRequest orderRequest) {
        if (orderRequest == null || asyncOrderInFlightService == null) {
            return;
        }
        ActivityScope activityScope = ActivityScope.from(
                orderRequest.getShowId(),
                orderRequest.getSessionId(),
                orderRequest.getTicketCategoryId()
        );
        asyncOrderInFlightService.release(activityScope.scopeKey(), orderRequest.getTicketCategoryId());
    }

    private void releaseAsyncOrderInFlight(Long ticketCategoryId) {
        if (asyncOrderInFlightService == null) {
            return;
        }
        asyncOrderInFlightService.release(ticketCategoryId);
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
                resolveAsyncCreateOrderTopic(),
                resolveAsyncCreateOrderTopic(),
                resolveAsyncCreateOrderKey(message),
                null,
                exceptionType,
                reason
        );
    }

    private String resolveAsyncCreateOrderTopic() {
        if (asyncOrderSubmitProperties == null) {
            return "smart-ticket.async-order.create";
        }
        return asyncOrderSubmitProperties.getKafkaAsyncCreateOrderTopic();
    }

    private String resolveAsyncCreateOrderKey(AsyncCreateOrderMessage message) {
        if (message == null) {
            return "request:unknown";
        }
        if (message.getRoutingPartitionKey() != null && !message.getRoutingPartitionKey().isBlank()) {
            return message.getRoutingPartitionKey().trim();
        }
        return "request:" + (message.getRequestId() == null ? "unknown" : message.getRequestId());
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
