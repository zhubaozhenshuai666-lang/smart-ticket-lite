package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RocketMqAsyncOrderMessagePublisherTest {

    private RocketMQTemplate rocketMQTemplate;

    private AsyncOrderSubmitProperties properties;

    private RocketMqAsyncOrderMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        properties = new AsyncOrderSubmitProperties();
        properties.setRocketMqAsyncCreateOrderTopic("order-create-topic");
        publisher = new RocketMqAsyncOrderMessagePublisher(rocketMQTemplate, properties);
    }

    @Test
    void rocketMqPublisherSendsOrderlyMessageWithPartitionKey() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 2L, 3L, 4L, 1);
        message.setStockBucketVersion(1);
        message.setStockBucketNo(7);

        String messageId = publisher.publish("MSGREQ1", message);

        assertThat(messageId).isEqualTo("MSGREQ1");
        assertThat(message.getMessageId()).isEqualTo("MSGREQ1");
        verify(rocketMQTemplate).syncSendOrderly("order-create-topic", message, "ticket:4:v1:bucket:7");
    }
}
