package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.order-timeout", name = "publisher-mode", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${smart-ticket.order-timeout.rocket-mq-order-timeout-topic}",
        consumerGroup = "${smart-ticket.order-timeout.rocket-mq-order-timeout-consumer-group}"
)
public class RocketMqOrderTimeoutConsumer implements RocketMQListener<OrderTimeoutMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqOrderTimeoutConsumer.class);

    private final OrderService orderService;

    public RocketMqOrderTimeoutConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(OrderTimeoutMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty order timeout RocketMQ message");
            return;
        }
        if (message.getExpireTime() != null && message.getExpireTime().isAfter(LocalDateTime.now())) {
            LOGGER.info("Skipped early order timeout RocketMQ message, orderId={}, expireTime={}, traceId={}",
                    message.getOrderId(), message.getExpireTime(), message.getTraceId());
            return;
        }
        LOGGER.info("Received RocketMQ order timeout close message, orderId={}, orderNo={}, traceId={}",
                message.getOrderId(), message.getOrderNo(), message.getTraceId());
        orderService.closeTimeoutOrder(message.getOrderId());
    }
}
