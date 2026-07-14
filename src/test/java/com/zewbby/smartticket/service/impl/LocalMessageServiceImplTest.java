package com.zewbby.smartticket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.LocalMessageProperties;
import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.domain.entity.LocalMessage;
import com.zewbby.smartticket.enums.LocalMessageStatusEnum;
import com.zewbby.smartticket.mapper.LocalMessageMapper;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalMessageServiceImplTest {

    @Mock
    private LocalMessageMapper localMessageMapper;

    private LocalMessageServiceImpl localMessageService;

    private LocalMessageProperties properties;

    @BeforeEach
    void setUp() {
        properties = new LocalMessageProperties();
        properties.setDefaultMaxRetryCount(5);
        localMessageService = new LocalMessageServiceImpl(
                localMessageMapper,
                new ObjectMapper().findAndRegisterModules(),
                properties,
                new AsyncOrderSubmitProperties(),
                new OrderTimeoutProperties(),
                new AsyncOrderPartitionService()
        );
    }

    @Test
    void createAsyncCreateOrderMessagePersistsInitLocalMessage() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenReturn(1);
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);

        String messageId = localMessageService.createAsyncCreateOrderMessage(message);

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(captor.capture());
        LocalMessage localMessage = captor.getValue();
        assertThat(messageId).isEqualTo("MSGREQ1");
        assertThat(localMessage.getMessageId()).isEqualTo("MSGREQ1");
        assertThat(localMessage.getStatus()).isEqualTo(LocalMessageStatusEnum.INIT.getCode());
        assertThat(localMessage.getBusinessType()).isEqualTo("ASYNC_CREATE_ORDER");
        assertThat(localMessage.getBusinessKey()).isEqualTo("REQ1");
        assertThat(localMessage.getRetryCount()).isZero();
        assertThat(localMessage.getMaxRetryCount()).isEqualTo(5);
        assertThat(localMessage.getPayload()).contains("\"requestId\":\"REQ1\"");
    }

    @Test
    void createAsyncCreateOrderMessageCanUseProvidedMessageId() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenReturn(1);
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);

        String messageId = localMessageService.createAsyncCreateOrderMessage("MSG_FIXED", message);

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(captor.capture());
        assertThat(messageId).isEqualTo("MSG_FIXED");
        assertThat(captor.getValue().getMessageId()).isEqualTo("MSG_FIXED");
    }

    @Test
    void createAsyncCreateOrderMessageUsesKafkaPartitionKey() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenReturn(1);
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 6L, 1);

        localMessageService.createAsyncCreateOrderMessage(message);

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(captor.capture());
        assertThat(captor.getValue().getExchangeName()).isEqualTo("smart-ticket.async-order.create");
        assertThat(captor.getValue().getRoutingKey()).isEqualTo("ticket:6");
    }

    @Test
    void createOrderTimeoutCloseMessagePersistsInitLocalMessage() {
        when(localMessageMapper.insert(any(LocalMessage.class))).thenReturn(1);
        OrderTimeoutMessage message = new OrderTimeoutMessage();
        message.setOrderId(100L);
        message.setOrderNo("ST100");
        message.setUserId(1L);
        message.setExpireTime(LocalDateTime.now().plusMinutes(10));

        String messageId = localMessageService.createOrderTimeoutCloseMessage(message);

        ArgumentCaptor<LocalMessage> captor = ArgumentCaptor.forClass(LocalMessage.class);
        verify(localMessageMapper).insert(captor.capture());
        LocalMessage localMessage = captor.getValue();
        assertThat(messageId).startsWith("MSG");
        assertThat(localMessage.getStatus()).isEqualTo(LocalMessageStatusEnum.INIT.getCode());
        assertThat(localMessage.getBusinessType()).isEqualTo("ORDER_TIMEOUT_CLOSE");
        assertThat(localMessage.getBusinessKey()).isEqualTo("100");
        assertThat(localMessage.getExchangeName()).isEqualTo("smart-ticket.order.timeout");
        assertThat(localMessage.getRoutingKey()).isEqualTo("order:100");
        assertThat(localMessage.getPayload()).contains("\"orderId\":100");
    }

    @Test
    void markPublishFailedBeforeMaxRetryKeepsMessageRetryable() {
        LocalMessage message = localMessage(1);

        localMessageService.markPublishFailed(message, "send failed");

        ArgumentCaptor<LocalDateTime> nextRetryCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(localMessageMapper).markPublishFailedById(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("send failed"),
                nextRetryCaptor.capture(),
                org.mockito.ArgumentMatchers.isNull()
        );
        assertThat(nextRetryCaptor.getValue()).isAfter(LocalDateTime.now().minusSeconds(1));
    }

    @Test
    void markPublishFailedAtMaxRetryMarksDead() {
        LocalMessage message = localMessage(4);

        localMessageService.markPublishFailed(message, "send failed");

        ArgumentCaptor<LocalDateTime> deadAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(localMessageMapper).markPublishFailedById(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("send failed"),
                org.mockito.ArgumentMatchers.isNull(),
                deadAtCaptor.capture()
        );
        assertThat(deadAtCaptor.getValue()).isNotNull();
    }

    @Test
    void manualRetryResetsFailedOrDeadMessageToInit() {
        when(localMessageMapper.resetForManualRetry("MSG1")).thenReturn(1);

        localMessageService.retryManually("MSG1");

        verify(localMessageMapper).resetForManualRetry("MSG1");
    }

    @Test
    void confirmAckMarksMessageConfirmed() {
        localMessageService.markConfirmed("MSG1");

        verify(localMessageMapper).markConfirmed(anyString(), any(LocalDateTime.class));
    }

    @Test
    void claimPublishableMessagesMarksTheLockedBatchAsSending() {
        LocalMessage first = localMessage(0);
        LocalMessage second = localMessage(0);
        second.setId(2L);
        second.setMessageId("MSG2");
        when(localMessageMapper.selectPublishableMessagesForUpdate(any(LocalDateTime.class), any()))
                .thenReturn(List.of(first, second));
        when(localMessageMapper.markSendingBatch(List.of(1L, 2L))).thenReturn(2);

        List<LocalMessage> claimed = localMessageService.claimPublishableMessages(LocalDateTime.now(), 100);

        assertThat(claimed).containsExactly(first, second);
        assertThat(claimed).extracting(LocalMessage::getStatus)
                .containsOnly(LocalMessageStatusEnum.SENDING.getCode());
        verify(localMessageMapper).markSendingBatch(List.of(1L, 2L));
    }

    private LocalMessage localMessage(Integer retryCount) {
        LocalMessage message = new LocalMessage();
        message.setId(1L);
        message.setMessageId("MSG1");
        message.setRetryCount(retryCount);
        message.setMaxRetryCount(5);
        return message;
    }
}
