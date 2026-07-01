package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.OrderService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RocketMqOrderTimeoutConsumerTest {

    @Test
    void rocketMqOrderTimeoutConsumerClosesExpiredOrder() {
        OrderService orderService = mock(OrderService.class);
        RocketMqOrderTimeoutConsumer consumer = new RocketMqOrderTimeoutConsumer(orderService);
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");
        message.setExpireTime(LocalDateTime.now().minusSeconds(1));

        consumer.onMessage(message);

        verify(orderService).closeTimeoutOrder(1L);
    }

    @Test
    void rocketMqOrderTimeoutConsumerSkipsEarlyMessage() {
        OrderService orderService = mock(OrderService.class);
        RocketMqOrderTimeoutConsumer consumer = new RocketMqOrderTimeoutConsumer(orderService);
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");
        message.setExpireTime(LocalDateTime.now().plusMinutes(1));

        consumer.onMessage(message);

        verify(orderService, never()).closeTimeoutOrder(1L);
    }
}
