package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.RabbitMqConstant;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutProducer {

    private final RabbitTemplate rabbitTemplate;

    public OrderTimeoutProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendOrderTimeoutMessage(Long orderId, String orderNo) {
        rabbitTemplate.convertAndSend(
                RabbitMqConstant.ORDER_TIMEOUT_DELAY_EXCHANGE,
                RabbitMqConstant.ORDER_TIMEOUT_DELAY_ROUTING_KEY,
                new OrderTimeoutMessage(orderId, orderNo)
        );
    }
}
