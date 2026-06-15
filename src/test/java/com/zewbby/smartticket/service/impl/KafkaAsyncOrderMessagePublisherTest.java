package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class KafkaAsyncOrderMessagePublisherTest {

    private KafkaTemplate<String, AsyncCreateOrderMessage> kafkaTemplate;

    private AsyncOrderSubmitProperties properties;

    private KafkaAsyncOrderMessagePublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new AsyncOrderSubmitProperties();
        properties.setKafkaAsyncCreateOrderTopic("order-create-topic");
        publisher = new KafkaAsyncOrderMessagePublisher(kafkaTemplate, properties);
    }

    @Test
    void kafkaPublisherSendsMessageWithPartitionKey() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 2L, 3L, 4L, 1);
        message.setStockBucketVersion(1);
        message.setStockBucketNo(7);

        String messageId = publisher.publish("MSGREQ1", message);

        assertThat(messageId).isEqualTo("MSGREQ1");
        assertThat(message.getMessageId()).isEqualTo("MSGREQ1");
        verify(kafkaTemplate).send("order-create-topic", "ticket:4:v1:bucket:7", message);
    }

    @Test
    void kafkaPublisherGeneratesMessageIdWhenMissing() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ2", 1L, 2L, 3L, 4L, 1);
        ArgumentCaptor<AsyncCreateOrderMessage> messageCaptor = ArgumentCaptor.forClass(AsyncCreateOrderMessage.class);

        String messageId = publisher.publish(message);

        assertThat(messageId).isEqualTo("MSGREQ2");
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("order-create-topic"),
                org.mockito.ArgumentMatchers.eq("ticket:4"),
                messageCaptor.capture()
        );
        assertThat(messageCaptor.getValue().getMessageId()).isEqualTo("MSGREQ2");
    }
}
