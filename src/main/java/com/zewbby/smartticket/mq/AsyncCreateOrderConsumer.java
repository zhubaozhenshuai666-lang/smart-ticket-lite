package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.ErrorMessageConstant;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.service.StockLuaService;
import com.zewbby.smartticket.domain.entity.TicketCategory;
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
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
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

    private final OrderTimeoutProducer orderTimeoutProducer;

    private final StockLuaService stockLuaService;

    private final DeadLetterMessageService deadLetterMessageService;

    private final MqConsumerProperties mqConsumerProperties;

    public AsyncCreateOrderConsumer(OrderRequestMapper orderRequestMapper,
                                    OrderMapper orderMapper,
                                    UserMapper userMapper,
                                    TicketCategoryMapper ticketCategoryMapper,
                                    TicketStockMapper ticketStockMapper,
                                    OrderTimeoutProducer orderTimeoutProducer,
                                    StockLuaService stockLuaService,
                                    DeadLetterMessageService deadLetterMessageService,
                                    MqConsumerProperties mqConsumerProperties) {
        this.orderRequestMapper = orderRequestMapper;
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.orderTimeoutProducer = orderTimeoutProducer;
        this.stockLuaService = stockLuaService;
        this.deadLetterMessageService = deadLetterMessageService;
        this.mqConsumerProperties = mqConsumerProperties;
    }

    @RabbitListener(queues = RabbitMqConstant.ORDER_ASYNC_QUEUE, containerFactory = "asyncOrderRabbitListenerContainerFactory")
    @Transactional
    public void consume(AsyncCreateOrderMessage message) {
        LOGGER.info("Received async create order message, requestId={}", message.getRequestId());

        /*
         * RabbitMQ 默认只能保证“至少一次投递”，网络抖动、消费者异常、ACK 丢失都可能导致同一条消息再次投递。
         * Publisher Confirm 只证明 Broker 收到消息，不证明消费者成功处理；可靠投递不等于可靠消费。
         * 所以这里不能相信“我只会消费一次”，也不能只靠内存锁；真正的幂等开关必须落在数据库状态机上。
         * 只有 PRE_DEDUCTED/QUEUED 能通过条件更新进入 PROCESSING，重复消息、成功消息、失败消息都会被挡住。
         */
        TicketOrderRequest existingRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (existingRequest == null) {
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY, "异步下单请求不存在");
            LOGGER.warn("Recorded dead letter because async order request does not exist, requestId={}",
                    message.getRequestId());
            return;
        }
        if (OrderRequestStatusEnum.SUCCESS.getCode().equals(existingRequest.getStatus())) {
            LOGGER.info("Skipped duplicated async order message because request already SUCCESS, requestId={}",
                    existingRequest.getRequestId());
            return;
        }
        if (OrderRequestStatusEnum.FAILED.getCode().equals(existingRequest.getStatus())
                || OrderRequestStatusEnum.COMPENSATED.getCode().equals(existingRequest.getStatus())
                || OrderRequestStatusEnum.CANCELLED.getCode().equals(existingRequest.getStatus())) {
            LOGGER.info("Skipped async order request with terminal or in-flight status, requestId={}, status={}",
                    existingRequest.getRequestId(), existingRequest.getStatus());
            return;
        }
        if (OrderRequestStatusEnum.PROCESSING.getCode().equals(existingRequest.getStatus())) {
            handleProcessingRequest(message, existingRequest);
            return;
        }
        if (!OrderRequestStatusEnum.canEnterProcessing(existingRequest.getStatus())) {
            recordDeadLetter(message, ConsumerExceptionTypeEnum.DATA_INCONSISTENCY,
                    "异步下单请求状态不允许消费: " + existingRequest.getStatus());
            LOGGER.info("Skipped async order request that is not ready for consuming, requestId={}, status={}",
                    existingRequest.getRequestId(), existingRequest.getStatus());
            return;
        }

        int claimedRows = orderRequestMapper.tryMarkProcessing(message.getRequestId());
        if (claimedRows != 1) {
            LOGGER.info("Skipped async order request because another consumer has claimed it, requestId={}",
                    message.getRequestId());
            return;
        }

        TicketOrderRequest orderRequest = orderRequestMapper.selectProcessingByRequestId(message.getRequestId());
        if (orderRequest == null) {
            LOGGER.info("Skipped async order request after lock, requestId={} is no longer PROCESSING",
                    message.getRequestId());
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

            TicketCategory ticketCategory = getValidTicketCategory(message, orderRequest);
            if (ticketCategory == null) {
                return;
            }

            /*
             * Redis 预扣只是入口削峰和快速失败，MySQL 才是最终持久化库存。
             * 即使 Redis 已经扣成功，这里也必须用 available_stock >= quantity 的条件更新再扣一次 MySQL，
             * 防止 Redis 重建、预热覆盖、人工修复等场景把缓存和数据库库存搞到不一致后造成超卖。
             */
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

            LocalDateTime now = LocalDateTime.now();
            TicketOrder order = new TicketOrder();
            order.setOrderNo(generateOrderNo());
            order.setUserId(orderRequest.getUserId());
            order.setShowId(orderRequest.getShowId());
            order.setSessionId(orderRequest.getSessionId());
            order.setTicketCategoryId(orderRequest.getTicketCategoryId());
            order.setQuantity(orderRequest.getQuantity());
            order.setTotalAmount(calculateTotalAmount(ticketCategory.getPrice(), orderRequest.getQuantity()));
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
            LOGGER.info("Created order for async request, requestId={}, orderId={}, orderNo={}",
                    orderRequest.getRequestId(), order.getId(), order.getOrderNo());

            //发一个超时信息到超时exchange上
            orderTimeoutProducer.sendOrderTimeoutMessage(order.getId(), order.getOrderNo());

            //异步请求下单
            int successRows = orderRequestMapper.markSuccess(orderRequest.getId(), order.getId());
            if (successRows != 1) {
                throw new IllegalStateException("异步下单请求状态更新失败");
            }
            LOGGER.info("Marked async order request SUCCESS, requestId={}, orderId={}",
                    orderRequest.getRequestId(), order.getId());
        } catch (AmqpException exception) {
            LOGGER.error("Failed to send timeout message for async order, requestId={}",
                    message.getRequestId(), exception);
            throw new ConsumerRetryableException(
                    ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR,
                    "发送订单超时消息失败",
                    exception
            );
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

    private TicketCategory getValidTicketCategory(AsyncCreateOrderMessage message, TicketOrderRequest orderRequest) {
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

        TicketCategory ticketCategory = ticketCategoryMapper.selectById(orderRequest.getTicketCategoryId());
        if (ticketCategory == null) {
            LOGGER.warn("Async create order failed, requestId={}, reason={}",
                    orderRequest.getRequestId(), ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
            markBusinessRejected(message, orderRequest, ErrorMessageConstant.TICKET_CATEGORY_NOT_FOUND);
        }
        return ticketCategory;
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
