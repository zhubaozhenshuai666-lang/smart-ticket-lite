package com.zewbby.smartticket.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeadLetterMessageRecovererTest {

    @Mock
    private DeadLetterMessageService deadLetterMessageService;

    private DeadLetterMessageRecoverer recoverer;

    @BeforeEach
    void setUp() {
        recoverer = new DeadLetterMessageRecoverer(deadLetterMessageService, new ObjectMapper());
    }

    @Test
    void recoverPersistsDeadLetterAfterRetryExhausted() {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue("order.async.queue");
        properties.setReceivedExchange("order.async.exchange");
        properties.setReceivedRoutingKey("order.async.create");
        properties.setMessageId("MSG1");
        Message message = new Message(
                "{\"requestId\":\"REQ1\",\"userId\":1,\"showId\":1,\"sessionId\":1,\"ticketCategoryId\":2,\"quantity\":1}".getBytes(),
                properties
        );

        recoverer.recover(
                message,
                new ConsumerRetryableException(
                        ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR,
                        "数据库短暂异常",
                        new RuntimeException("connection reset")
                )
        );

        verify(deadLetterMessageService).recordAsyncCreateOrderDeadLetter(
                any(AsyncCreateOrderMessage.class),
                eq("order.async.queue"),
                eq("order.async.exchange"),
                eq("order.async.create"),
                eq("MSG1"),
                eq(ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR),
                eq("connection reset")
        );
    }

    @Test
    void recoverStoresRawPayloadWhenMessageCannotBeParsed() {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue("order.async.queue");
        Message message = new Message("not-json".getBytes(), properties);

        recoverer.recover(message, new RuntimeException("bad payload"));

        verify(deadLetterMessageService).recordDeadLetter(
                any(),
                anyString(),
                eq("UNKNOWN"),
                eq("order.async.queue"),
                any(),
                any(),
                eq("not-json"),
                eq(ConsumerExceptionTypeEnum.DATA_INCONSISTENCY),
                org.mockito.ArgumentMatchers.contains("消息反序列化失败")
        );
    }
}
