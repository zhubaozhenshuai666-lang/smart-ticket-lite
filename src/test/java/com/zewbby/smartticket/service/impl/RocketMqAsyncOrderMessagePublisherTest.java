package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderTransactionMarkerService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void rocketMqPublisherSendsTransactionalMessageAndExecutesLocalTransaction() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 2L, 3L, 4L, 1);
        AtomicBoolean localTransactionExecuted = new AtomicBoolean(false);
        when(rocketMQTemplate.sendMessageInTransaction(eq("order-create-topic"), any(Message.class), any()))
                .thenAnswer(invocation -> {
                    Message<?> rocketMessage = invocation.getArgument(1);
                    Object arg = invocation.getArgument(2);
                    assertThat(publisher.executeLocalTransaction(rocketMessage, arg))
                            .isEqualTo(RocketMQLocalTransactionState.COMMIT);
                    TransactionSendResult result = new TransactionSendResult();
                    result.setLocalTransactionState(LocalTransactionState.COMMIT_MESSAGE);
                    return result;
                });

        String messageId = publisher.publishInTransaction("MSGREQ1", message,
                () -> localTransactionExecuted.set(true));

        assertThat(messageId).isEqualTo("MSGREQ1");
        assertThat(localTransactionExecuted).isTrue();
        ArgumentCaptor<Message<?>> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rocketMQTemplate).sendMessageInTransaction(eq("order-create-topic"), messageCaptor.capture(), any());
        assertThat(messageCaptor.getValue().getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo("REQ1");
    }

    @Test
    void checkLocalTransactionCommitsWhenDeductionMarkerExists() {
        AsyncOrderTransactionMarkerService markerService = mock(AsyncOrderTransactionMarkerService.class);
        publisher = new RocketMqAsyncOrderMessagePublisher(
                rocketMQTemplate,
                properties,
                new com.zewbby.smartticket.service.AsyncOrderPartitionService(),
                markerService
        );
        when(markerService.hasCommittedDeduction("REQ1")).thenReturn(true);
        Message<String> message = MessageBuilder.withPayload("payload")
                .setHeader(RocketMQHeaders.KEYS, "REQ1")
                .build();

        RocketMQLocalTransactionState state = publisher.checkLocalTransaction(message);

        assertThat(state).isEqualTo(RocketMQLocalTransactionState.COMMIT);
    }
}
