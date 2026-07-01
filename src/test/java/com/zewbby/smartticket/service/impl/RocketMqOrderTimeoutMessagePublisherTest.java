package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqOrderTimeoutMessagePublisherTest {

    private RocketMQTemplate rocketMQTemplate;

    private OrderTimeoutProperties properties;

    private RocketMqOrderTimeoutMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        properties = new OrderTimeoutProperties();
        properties.setRocketMqOrderTimeoutTopic("order-timeout-topic");
        properties.setRocketMqDelayLevel(16);
        properties.setRocketMqSendTimeoutMillis(3000);
        publisher = new RocketMqOrderTimeoutMessagePublisher(rocketMQTemplate, properties);
    }

    @Test
    void rocketMqPublisherSendsDelayMessage() {
        OrderTimeoutMessage message = new OrderTimeoutMessage(1L, "ORDER1");

        String messageId = publisher.publish(message);

        assertThat(messageId).startsWith("OT");
        assertThat(message.getMessageId()).isEqualTo(messageId);
        verify(rocketMQTemplate).syncSend(eq("order-timeout-topic"), any(Message.class), eq(3000L), eq(16));
    }
}
