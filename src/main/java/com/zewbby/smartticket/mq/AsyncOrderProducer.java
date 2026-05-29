package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.RabbitMqConstant;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class AsyncOrderProducer {

    private final RabbitTemplate rabbitTemplate;

    public AsyncOrderProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendAsyncCreateOrderMessage(AsyncCreateOrderMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMqConstant.ORDER_ASYNC_EXCHANGE,
                RabbitMqConstant.ORDER_ASYNC_ROUTING_KEY,
                message
        );
    }
}
