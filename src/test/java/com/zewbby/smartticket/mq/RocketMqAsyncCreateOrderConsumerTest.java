package com.zewbby.smartticket.mq;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RocketMqAsyncCreateOrderConsumerTest {

    @Test
    void rocketMqConsumerDelegatesToAsyncCreateOrderConsumer() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        RocketMqAsyncCreateOrderConsumer consumer = new RocketMqAsyncCreateOrderConsumer(delegate);
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);

        consumer.onMessage(message);

        verify(delegate).consume(message);
    }

    @Test
    void rocketMqConsumerIgnoresEmptyMessage() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        RocketMqAsyncCreateOrderConsumer consumer = new RocketMqAsyncCreateOrderConsumer(delegate);

        consumer.onMessage(null);

        verify(delegate, never()).consume(org.mockito.ArgumentMatchers.any());
    }
}
