package com.zewbby.smartticket.mq;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KafkaAsyncCreateOrderConsumerTest {

    @Test
    void kafkaConsumerDelegatesToAsyncCreateOrderConsumer() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        KafkaAsyncCreateOrderConsumer consumer = new KafkaAsyncCreateOrderConsumer(delegate);
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);

        consumer.consume(message);

        verify(delegate).consume(message);
    }

    @Test
    void kafkaConsumerIgnoresEmptyMessage() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        KafkaAsyncCreateOrderConsumer consumer = new KafkaAsyncCreateOrderConsumer(delegate);

        consumer.consume(null);

        verify(delegate, never()).consume(org.mockito.ArgumentMatchers.any());
    }
}
