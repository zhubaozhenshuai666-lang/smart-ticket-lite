package com.zewbby.smartticket.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.LocalMessageProperties;
import com.zewbby.smartticket.domain.entity.LocalMessage;
import com.zewbby.smartticket.domain.event.OrderCreatedEvent;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.service.LocalMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalMessagePublishTaskTest {

    @Mock
    private LocalMessageService localMessageService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private LocalMessageProperties properties;

    private LocalMessagePublishTask publishTask;

    @BeforeEach
    void setUp() {
        properties = new LocalMessageProperties();
        properties.setBatchSize(100);
        properties.setConfirmTimeoutSeconds(60);
        publishTask = new LocalMessagePublishTask(
                localMessageService,
                kafkaTemplate,
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
        lenient().when(kafkaTemplate.send(any(String.class), any(String.class), any(Object.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void senderClaimsAndSendsPublishableMessages() {
        LocalMessage message = localMessage();
        when(localMessageService.claimPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));

        publishTask.publishPendingMessages();

        verify(kafkaTemplate).send(eq("smart-ticket.async-order.create"), eq("order.async.create"), any(AsyncCreateOrderMessage.class));
        verify(localMessageService).markConfirmed("MSG1");
        verify(localMessageService, never()).markSent(1L);
    }

    @Test
    void senderCanPublishOrderTimeoutCloseMessageThroughOutbox() {
        LocalMessage message = timeoutLocalMessage();
        when(localMessageService.claimPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));

        publishTask.publishPendingMessages();

        verify(kafkaTemplate).send(eq("smart-ticket.order.timeout"), eq("order:100"), any(OrderTimeoutMessage.class));
        verify(localMessageService).markConfirmed("MSG_TIMEOUT");
        verify(localMessageService, never()).markSent(2L);
    }

    @Test
    void senderCanPublishOrderCreatedDomainEventThroughOutbox() {
        LocalMessage message = orderCreatedEventMessage();
        when(localMessageService.claimPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));

        publishTask.publishPendingMessages();

        verify(kafkaTemplate).send(eq("smart-ticket.event.order-created"), eq("order:100"), any(OrderCreatedEvent.class));
        verify(localMessageService).markConfirmed("MSG_EVENT");
    }

    @Test
    void senderCanImmediatelyPublishOneMessageByMessageId() {
        LocalMessage message = localMessage();
        when(localMessageService.getByMessageId("MSG1")).thenReturn(message);
        when(localMessageService.tryMarkSending(message)).thenReturn(true);

        publishTask.publishByMessageId("MSG1");

        verify(kafkaTemplate).send(eq("smart-ticket.async-order.create"), eq("order.async.create"), any(AsyncCreateOrderMessage.class));
        verify(localMessageService).markConfirmed("MSG1");
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
    void senderSkipsWhenNoMessageIsClaimed() {
        when(localMessageService.claimPublishableMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of());

        publishTask.publishPendingMessages();

        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void senderMarksFailedWhenKafkaTemplateThrows() {
        LocalMessage message = localMessage();
        doThrow(new RuntimeException("send failed"))
                .when(kafkaTemplate)
                .send(any(String.class), any(String.class), any(Object.class));

        publishTask.publishOne(message);

        verify(localMessageService).markPublishFailed(message, "send failed");
    }

    @Test
    void confirmTimeoutScannerMarksInFlightMessagesFailed() {
        LocalMessage message = localMessage();
        when(localMessageService.selectConfirmTimeoutMessages(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(message));

        publishTask.scanConfirmTimeoutMessages();

        verify(localMessageService).markPublishFailed(message, "Kafka发送确认超时");
    }

    private LocalMessage localMessage() {
        LocalMessage message = new LocalMessage();
        message.setId(1L);
        message.setMessageId("MSG1");
        message.setBusinessType("ASYNC_CREATE_ORDER");
        message.setBusinessKey("REQ1");
        message.setExchangeName("smart-ticket.async-order.create");
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
        message.setExchangeName("smart-ticket.order.timeout");
        message.setRoutingKey("order:100");
        message.setPayload("{\"orderId\":100,\"orderNo\":\"ST100\",\"userId\":1,\"expireTime\":\"2026-06-02T16:00:00\",\"traceId\":null,\"messageId\":null}");
        message.setRetryCount(0);
        message.setMaxRetryCount(5);
        return message;
    }

    private LocalMessage orderCreatedEventMessage() {
        LocalMessage message = new LocalMessage();
        message.setId(3L);
        message.setMessageId("MSG_EVENT");
        message.setBusinessType("ORDER_CREATED_EVENT");
        message.setBusinessKey("100");
        message.setExchangeName("smart-ticket.event.order-created");
        message.setRoutingKey("order:100");
        message.setPayload("{\"eventId\":\"EVT1\",\"eventType\":\"ORDER_CREATED\",\"orderId\":100,\"orderNo\":\"ST100\",\"userId\":1,\"showId\":1,\"sessionId\":1,\"ticketCategoryId\":2,\"quantity\":1,\"totalAmount\":880.00,\"occurredAt\":\"2026-06-02T16:00:00\"}");
        message.setRetryCount(0);
        message.setMaxRetryCount(5);
        return message;
    }
}
