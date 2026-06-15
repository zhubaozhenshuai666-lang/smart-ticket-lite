package com.zewbby.smartticket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.domain.entity.DeadLetterMessage;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.enums.CompensationStatusEnum;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.enums.DeadLetterStatusEnum;
import com.zewbby.smartticket.enums.LocalMessageBusinessTypeEnum;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.mapper.DeadLetterMessageMapper;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterMessageServiceImplTest {

    @Mock
    private DeadLetterMessageMapper deadLetterMessageMapper;

    @Mock
    private OrderRequestMapper orderRequestMapper;

    @Mock
    private AsyncOrderMessagePublisher asyncOrderMessagePublisher;

    private DeadLetterMessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DeadLetterMessageServiceImpl(
                deadLetterMessageMapper,
                orderRequestMapper,
                asyncOrderMessagePublisher,
                new ObjectMapper()
        );
    }

    @Test
    void recordAsyncCreateOrderDeadLetterPersistsPendingMessage() {
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);
        TicketOrderRequest request = retryableRequest(OrderRequestStatusEnum.QUEUED.getCode());
        request.setMessageId("MSG1");
        when(orderRequestMapper.selectByRequestId("REQ1")).thenReturn(request);

        service.recordAsyncCreateOrderDeadLetter(
                message,
                "order.async.queue",
                "order.async.exchange",
                "order.async.create",
                null,
                ConsumerExceptionTypeEnum.BUSINESS_REJECT,
                "库存不足"
        );

        ArgumentCaptor<DeadLetterMessage> captor = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(deadLetterMessageMapper).insert(captor.capture());
        DeadLetterMessage deadLetterMessage = captor.getValue();
        assertThat(deadLetterMessage.getMessageId()).isEqualTo("MSG1");
        assertThat(deadLetterMessage.getBusinessKey()).isEqualTo("REQ1");
        assertThat(deadLetterMessage.getExceptionType()).isEqualTo(ConsumerExceptionTypeEnum.BUSINESS_REJECT.getCode());
        assertThat(deadLetterMessage.getStatus()).isEqualTo(DeadLetterStatusEnum.PENDING.getCode());
        assertThat(deadLetterMessage.getPayload()).contains("\"requestId\":\"REQ1\"");
    }

    @Test
    void retryCreatesNewLocalMessageAndMarksDeadLetterRetried() {
        DeadLetterMessage deadLetterMessage = pendingDeadLetter();
        TicketOrderRequest request = retryableRequest(OrderRequestStatusEnum.QUEUED.getCode());
        when(deadLetterMessageMapper.selectById(1L)).thenReturn(deadLetterMessage);
        when(orderRequestMapper.selectByRequestId("REQ1")).thenReturn(request);
        when(asyncOrderMessagePublisher.publish(any(AsyncCreateOrderMessage.class))).thenReturn("MSG-NEW");
        when(orderRequestMapper.refreshQueuedMessage(10L, "MSG-NEW")).thenReturn(1);
        when(deadLetterMessageMapper.markRetried(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        service.retry(1L);

        verify(asyncOrderMessagePublisher).publish(any(AsyncCreateOrderMessage.class));
        verify(orderRequestMapper).refreshQueuedMessage(10L, "MSG-NEW");
        verify(deadLetterMessageMapper).markRetried(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void retryRejectsCompensatedRequestToAvoidBypassingRedisPreDeduct() {
        DeadLetterMessage deadLetterMessage = pendingDeadLetter();
        TicketOrderRequest request = retryableRequest(OrderRequestStatusEnum.COMPENSATED.getCode());
        request.setCompensated(true);
        request.setCompensationStatus(CompensationStatusEnum.COMPENSATED.getCode());
        when(deadLetterMessageMapper.selectById(1L)).thenReturn(deadLetterMessage);
        when(orderRequestMapper.selectByRequestId("REQ1")).thenReturn(request);

        assertThatThrownBy(() -> service.retry(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redis 预扣已补偿");
    }

    @Test
    void ignoreAndResolveUpdateDeadLetterStatus() {
        when(deadLetterMessageMapper.markIgnored(eq(1L), any(LocalDateTime.class))).thenReturn(1);
        when(deadLetterMessageMapper.markResolved(eq(2L), any(LocalDateTime.class))).thenReturn(1);

        service.ignore(1L);
        service.resolve(2L);

        verify(deadLetterMessageMapper).markIgnored(eq(1L), any(LocalDateTime.class));
        verify(deadLetterMessageMapper).markResolved(eq(2L), any(LocalDateTime.class));
    }

    private DeadLetterMessage pendingDeadLetter() {
        DeadLetterMessage deadLetterMessage = new DeadLetterMessage();
        deadLetterMessage.setId(1L);
        deadLetterMessage.setBusinessType(LocalMessageBusinessTypeEnum.ASYNC_CREATE_ORDER.getCode());
        deadLetterMessage.setBusinessKey("REQ1");
        deadLetterMessage.setPayload("{\"requestId\":\"REQ1\",\"userId\":1,\"showId\":1,\"sessionId\":1,\"ticketCategoryId\":2,\"quantity\":1}");
        deadLetterMessage.setStatus(DeadLetterStatusEnum.PENDING.getCode());
        return deadLetterMessage;
    }

    private TicketOrderRequest retryableRequest(String status) {
        TicketOrderRequest request = new TicketOrderRequest();
        request.setId(10L);
        request.setRequestId("REQ1");
        request.setStatus(status);
        request.setRedisDeducted(true);
        request.setCompensated(false);
        request.setCompensationStatus(CompensationStatusEnum.NONE.getCode());
        return request;
    }
}
