package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.task.LocalMessagePublishTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutProducerTest {

    @Mock
    private LocalMessageService localMessageService;

    @Mock
    private LocalMessagePublishTask localMessagePublishTask;

    @Mock
    private KafkaTemplate<String, OrderTimeoutMessage> kafkaTemplate;

    private OrderTimeoutProperties properties;

    private OrderTimeoutProducer producer;

    @BeforeEach
    void setUp() {
        properties = new OrderTimeoutProperties();
        producer = new OrderTimeoutProducer(localMessageService, localMessagePublishTask, kafkaTemplate, properties);
    }

    @Test
    void kafkaModePublishesTimeoutMessageWithoutOutboxWrite() {
        properties.setDelayMessageEnabled(true);
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");

        String messageId = producer.sendOrderTimeoutMessage(message);

        assertThat(messageId).startsWith("OT");
        assertThat(message.getMessageId()).isEqualTo(messageId);
        verify(kafkaTemplate).send("smart-ticket.order.timeout", "order:1", message);
        verify(localMessageService, never()).createOrderTimeoutCloseMessage(message);
    }

    @Test
    void outboxModeKeepsReliableLocalMessagePath() {
        properties.setDelayMessageEnabled(true);
        properties.setPublisherMode(OrderTimeoutProperties.PUBLISHER_MODE_OUTBOX);
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");
        when(localMessageService.createOrderTimeoutCloseMessage(message)).thenReturn("MSG1");

        String messageId = producer.sendOrderTimeoutMessage(message);

        assertThat(messageId).isEqualTo("MSG1");
        verify(localMessagePublishTask).publishByMessageId("MSG1");
        verify(kafkaTemplate, never()).send("smart-ticket.order.timeout", "order:1", message);
    }

    @Test
    void disabledDelayMessageReturnsNull() {
        properties.setDelayMessageEnabled(false);

        assertThat(producer.sendOrderTimeoutMessage(new OrderTimeoutMessage(1L, "ORDER1"))).isNull();
        verify(kafkaTemplate, never()).send("smart-ticket.order.timeout", "order:1", new OrderTimeoutMessage(1L, "ORDER1"));
    }
}
