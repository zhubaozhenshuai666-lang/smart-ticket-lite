package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DirectRabbitAsyncOrderMessagePublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private AsyncOrderSubmitProperties properties;

    private DirectRabbitAsyncOrderMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new AsyncOrderSubmitProperties();
        properties.setDirectRabbitWaitForConfirm(false);
        MqConsumerProperties mqConsumerProperties = new MqConsumerProperties();
        mqConsumerProperties.setAsyncQueueShardCount(16);
        publisher = new DirectRabbitAsyncOrderMessagePublisher(rabbitTemplate, mqConsumerProperties, properties);
    }

    @Test
    void directPublisherRoutesByTicketCategoryShard() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);

        publisher.publish("MSGREQ1", message);

        verify(rabbitTemplate).convertAndSend(
                eq("order.async.exchange"),
                eq("order.async.create.2"),
                eq(message),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );
    }

    @Test
    void directPublisherFailsFastWhenBrokerNacks() {
        properties.setDirectRabbitWaitForConfirm(true);
        properties.setDirectRabbitConfirmTimeoutMillis(1000L);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker busy"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                any(String.class),
                any(String.class),
                any(Object.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertThatThrownBy(() -> publisher.publish("MSGREQ1",
                new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("broker busy");
    }
}
