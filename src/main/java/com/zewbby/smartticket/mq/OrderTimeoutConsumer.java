package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.service.OrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.order-timeout", name = "delay-message-enabled", havingValue = "true")
public class OrderTimeoutConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderTimeoutConsumer.class);

    private final OrderService orderService;

    public OrderTimeoutConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMqConstant.ORDER_TIMEOUT_DEAD_QUEUE)
    public void consume(OrderTimeoutMessage message) {
        /*
         * 延迟消息也可能重复投递，且 Publisher Confirm 只说明 Broker 收到消息，不说明关闭订单成功。
         * 因此消费者必须重新查数据库状态：PAID/CANCELLED/CLOSED 都要跳过，只有 PENDING_PAYMENT 才允许关闭。
         * closeTimeoutOrder 内部使用条件更新和库存 locked_stock 回滚，保证重复消费不会重复释放库存。
         */
        LOGGER.info("Received order timeout close message, orderId={}, orderNo={}, traceId={}",
                message.getOrderId(), message.getOrderNo(), message.getTraceId());
        orderService.closeTimeoutOrder(message.getOrderId());
    }
}
