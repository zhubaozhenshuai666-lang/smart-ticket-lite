package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.service.OrderTimeoutMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutProducerTest {

    @Mock
    private OrderTimeoutMessagePublisher orderTimeoutMessagePublisher;

    private OrderTimeoutProperties properties;

    private OrderTimeoutProducer producer;

    @BeforeEach
    void setUp() {
        properties = new OrderTimeoutProperties();
        producer = new OrderTimeoutProducer(orderTimeoutMessagePublisher, properties);
    }

    @Test
    void enabledDelayMessageDelegatesToConfiguredPublisher() {
        properties.setDelayMessageEnabled(true);
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");
        when(orderTimeoutMessagePublisher.publish(message)).thenReturn("OT1");

        String messageId = producer.sendOrderTimeoutMessage(message);

        assertThat(messageId).isEqualTo("OT1");
        verify(orderTimeoutMessagePublisher).publish(message);
    }

    @Test
    void disabledDelayMessageReturnsNull() {
        properties.setDelayMessageEnabled(false);
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");

        assertThat(producer.sendOrderTimeoutMessage(message)).isNull();
        verify(orderTimeoutMessagePublisher, never()).publish(message);
    }
}
