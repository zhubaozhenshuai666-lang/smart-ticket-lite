package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.service.OrderService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutConsumer {

    private final OrderService orderService;

    public OrderTimeoutConsumer(OrderService orderService) {
        this.orderService = orderService;
    }

    @RabbitListener(queues = RabbitMqConstant.ORDER_TIMEOUT_DEAD_QUEUE)
    public void consume(OrderTimeoutMessage message) {
        orderService.closeTimeoutOrder(message.getOrderId());
    }
}
