package com.zewbby.smartticket.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.LocalMessageProperties;
import com.zewbby.smartticket.domain.entity.LocalMessage;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.service.LocalMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalMessagePublishTaskTest {

    @Mock
    private LocalMessageService localMessageService;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LocalMessageProperties properties;

    private LocalMessagePublishTask publishTask;

    @BeforeEach
    void setUp() {
        properties = new LocalMessageProperties();
        properties.setBatchSize(100);
        properties.setConfirmTimeoutSeconds(60);
        publishTask = new LocalMessagePublishTask(
                localMessageService,
                rabbitTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
    }

    @Test
    void senderScansInitOrFailedMessagesClaimsAndSends() {
        LocalMessage message = localMessage();
        when(localMessageService.selectPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));
        when(localMessageService.tryMarkSending(message)).thenReturn(true);

        publishTask.publishPendingMessages();

        verify(rabbitTemplate).convertAndSend(
                eq("order.async.exchange"),
                eq("order.async.create"),
                any(AsyncCreateOrderMessage.class),
                any(MessagePostProcessor.class),
                argThat((CorrelationData correlationData) -> "MSG1".equals(correlationData.getId()))
        );
        verify(localMessageService, never()).markSent(1L);
    }

    @Test
    void senderCanPublishOrderTimeoutCloseMessageThroughOutbox() {
        LocalMessage message = timeoutLocalMessage();
        when(localMessageService.selectPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));
        when(localMessageService.tryMarkSending(message)).thenReturn(true);

        publishTask.publishPendingMessages();

        verify(rabbitTemplate).convertAndSend(
                eq("smart-ticket.order.timeout.delay.exchange"),
                eq("smart-ticket.order.timeout.delay"),
                any(OrderTimeoutMessage.class),
                any(MessagePostProcessor.class),
                argThat((CorrelationData correlationData) -> "MSG_TIMEOUT".equals(correlationData.getId()))
        );
        verify(localMessageService, never()).markSent(2L);
    }

    @Test
    void senderCanImmediatelyPublishOneMessageByMessageId() {
        LocalMessage message = localMessage();
        when(localMessageService.getByMessageId("MSG1")).thenReturn(message);
        when(localMessageService.tryMarkSending(message)).thenReturn(true);

        publishTask.publishByMessageId("MSG1");

        verify(rabbitTemplate).convertAndSend(
                eq("order.async.exchange"),
                eq("order.async.create"),
                any(AsyncCreateOrderMessage.class),
                any(MessagePostProcessor.class),
                argThat((CorrelationData correlationData) -> "MSG1".equals(correlationData.getId()))
        );
        verify(localMessageService, never()).markSent(1L);
    }

    @Test
    void senderCanKeepSentStateWhenMarkSentIsEnabled() {
        properties.setMarkSentEnabled(true);
        LocalMessage message = localMessage();
        when(localMessageService.getByMessageId("MSG1")).thenReturn(message);
        when(localMessageService.tryMarkSending(message)).thenReturn(true);

        publishTask.publishByMessageId("MSG1");

        verify(localMessageService).markSent(1L);
    }

    @Test
    void senderSkipsWhenConditionalClaimFails() {
        LocalMessage message = localMessage();
        when(localMessageService.selectPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));
        when(localMessageService.tryMarkSending(message)).thenReturn(false);

        publishTask.publishPendingMessages();

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test
    void senderMarksFailedWhenRabbitTemplateThrows() {
        LocalMessage message = localMessage();
        doThrow(new RuntimeException("send failed")).when(rabbitTemplate).convertAndSend(
                any(String.class),
                any(String.class),
                any(Object.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        publishTask.publishOne(message);

        verify(localMessageService).markPublishFailed(message, "send failed");
    }

    @Test
    void confirmTimeoutScannerMarksInFlightMessagesFailed() {
        LocalMessage message = localMessage();
        when(localMessageService.selectConfirmTimeoutMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));

        publishTask.scanConfirmTimeoutMessages();

        verify(localMessageService).markPublishFailed(message, "Publisher Confirm超时");
    }

    private LocalMessage localMessage() {
        LocalMessage message = new LocalMessage();
        message.setId(1L);
        message.setMessageId("MSG1");
        message.setBusinessType("ASYNC_CREATE_ORDER");
        message.setBusinessKey("REQ1");
        message.setExchangeName("order.async.exchange");
        message.setRoutingKey("order.async.create");
        message.setPayload("{\"requestId\":\"REQ1\",\"userId\":1,\"showId\":1,\"sessionId\":1,\"ticketCategoryId\":2,\"quantity\":1}");
        message.setRetryCount(0);
        message.setMaxRetryCount(5);
        return message;
    }

    private LocalMessage timeoutLocalMessage() {
        LocalMessage message = new LocalMessage();
        message.setId(2L);
        message.setMessageId("MSG_TIMEOUT");
        message.setBusinessType("ORDER_TIMEOUT_CLOSE");
        message.setBusinessKey("100");
        message.setExchangeName("smart-ticket.order.timeout.delay.exchange");
        message.setRoutingKey("smart-ticket.order.timeout.delay");
        message.setPayload("{\"orderId\":100,\"orderNo\":\"ST100\",\"userId\":1,\"expireTime\":\"2026-06-02T16:00:00\",\"traceId\":null,\"messageId\":null}");
        message.setRetryCount(0);
        message.setMaxRetryCount(5);
        return message;
    }
}
