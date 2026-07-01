package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.PaymentCompensationProperties;
import com.zewbby.smartticket.mq.PaymentCompensationMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqPaymentCompensationPublisherTest {

    private RocketMQTemplate rocketMQTemplate;

    private PaymentCompensationProperties properties;

    private RocketMqPaymentCompensationPublisher publisher;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        properties = new PaymentCompensationProperties();
        properties.setRocketMqTopic("payment-compensation-topic");
        publisher = new RocketMqPaymentCompensationPublisher(rocketMQTemplate, properties);
    }

    @Test
    void rocketMqPublisherSendsOrderlyCompensationMessage() {
        PaymentCompensationMessage message = new PaymentCompensationMessage();
        message.setPaymentNo("PAY1");

        String messageId = publisher.publish(message);

        assertThat(messageId).startsWith("PC");
        assertThat(message.getMessageId()).isEqualTo(messageId);
        verify(rocketMQTemplate).syncSendOrderly("payment-compensation-topic", message, "payment:PAY1");
    }
}
