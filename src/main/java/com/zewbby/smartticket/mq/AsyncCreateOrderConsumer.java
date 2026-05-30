package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.service.StockLuaService;
import com.zewbby.smartticket.domain.entity.TicketCategory;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.domain.entity.UserAccount;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.OrderStatusEnum;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mapper.TicketCategoryMapper;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class AsyncCreateOrderConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCreateOrderConsumer.class);

    private static final String USER_NOT_FOUND = "用户不存在";

    private static final String TICKET_CATEGORY_NOT_FOUND = "票档不存在";

    private static final String STOCK_NOT_ENOUGH = "库存不足";

    private final OrderRequestMapper orderRequestMapper;

    private final OrderMapper orderMapper;

    private final UserMapper userMapper;

    private final TicketCategoryMapper ticketCategoryMapper;

    private final TicketStockMapper ticketStockMapper;

    private final OrderTimeoutProducer orderTimeoutProducer;

    private final StockLuaService stockLuaService;

    public AsyncCreateOrderConsumer(OrderRequestMapper orderRequestMapper,
                                    OrderMapper orderMapper,
                                    UserMapper userMapper,
                                    TicketCategoryMapper ticketCategoryMapper,
                                    TicketStockMapper ticketStockMapper,
                                    OrderTimeoutProducer orderTimeoutProducer,
                                    StockLuaService stockLuaService) {
        this.orderRequestMapper = orderRequestMapper;
        this.orderMapper = orderMapper;
        this.userMapper = userMapper;
        this.ticketCategoryMapper = ticketCategoryMapper;
        this.ticketStockMapper = ticketStockMapper;
        this.orderTimeoutProducer = orderTimeoutProducer;
        this.stockLuaService = stockLuaService;
    }

    @RabbitListener(queues = RabbitMqConstant.ORDER_ASYNC_QUEUE)
    @Transactional
    public void consume(AsyncCreateOrderMessage message) {
        LOGGER.info("Received async create order message, requestId={}", message.getRequestId());

        TicketOrderRequest existingRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (existingRequest == null) {
            LOGGER.warn("Ignored async create order message, requestId={} does not exist", message.getRequestId());
            return;
        }
        if (!OrderRequestStatusEnum.PROCESSING.getCode().equals(existingRequest.getStatus())) {
            LOGGER.info("Skipped processed async order request, requestId={}, status={}",
                    existingRequest.getRequestId(), existingRequest.getStatus());
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
                markFailedAndRollbackRedis(orderRequest, USER_NOT_FOUND);
                return;
            }

            TicketCategory ticketCategory = ticketCategoryMapper.selectById(orderRequest.getTicketCategoryId());
            if (ticketCategory == null) {
                LOGGER.warn("Async create order failed, requestId={}, reason={}",
                        orderRequest.getRequestId(), TICKET_CATEGORY_NOT_FOUND);
                markFailedAndRollbackRedis(orderRequest, TICKET_CATEGORY_NOT_FOUND);
                return;
            }

            //减库存
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
                markFailedAndRollbackRedis(orderRequest, STOCK_NOT_ENOUGH);
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
            order.setExpireTime(now.plusMinutes(15));
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
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to consume async create order message, requestId={}",
                    message.getRequestId(), exception);
            throw exception;
        }
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

    private void markFailedAndRollbackRedis(TicketOrderRequest orderRequest, String failReason) {
        if (!markFailed(orderRequest, failReason)) {
            return;
        }
        try {
            stockLuaService.rollbackStock(orderRequest.getTicketCategoryId(), orderRequest.getQuantity());
            LOGGER.info("Rolled back Redis stock for failed async request, requestId={}, ticketCategoryId={}, quantity={}",
                    orderRequest.getRequestId(),
                    orderRequest.getTicketCategoryId(),
                    orderRequest.getQuantity());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to rollback Redis stock for failed async request, requestId={}, send message to DLQ",
                    orderRequest.getRequestId(), exception.getMessage());
            throw new AmqpRejectAndDontRequeueException("Redis stock rollback failed", exception);
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
            throw new IllegalStateException("票档价格不存在");
        }
        return price.multiply(BigDecimal.valueOf(quantity)).intValue();
    }

    /**
     * 生成订单号
     * @return
     */
    private String generateOrderNo() {
        return "ST" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }
}
