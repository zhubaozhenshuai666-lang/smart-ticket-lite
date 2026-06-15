package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KafkaAsyncCreateOrderDeadLetterConsumerTest {

    @Test
    void dltConsumerPersistsDeadLetterMessage() {
        DeadLetterMessageService deadLetterMessageService = mock(DeadLetterMessageService.class);
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        properties.setKafkaAsyncCreateOrderTopic("order-create");
        properties.setKafkaAsyncCreateOrderDeadLetterTopic("order-create.DLT");
        KafkaAsyncCreateOrderDeadLetterConsumer consumer = new KafkaAsyncCreateOrderDeadLetterConsumer(
                deadLetterMessageService,
                properties,
                new AsyncOrderPartitionService()
        );
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 1L, 1L, 2L, 1);
        message.setMessageId("MSGREQ1");

        consumer.consume(message);

        verify(deadLetterMessageService).recordAsyncCreateOrderDeadLetter(
                message,
                "order-create.DLT",
                "order-create",
                "ticket:2",
                "MSGREQ1",
                ConsumerExceptionTypeEnum.UNKNOWN_ERROR,
                "Kafka异步创单消费重试耗尽，已进入DLT"
        );
    }

    @Test
    void dltConsumerIgnoresEmptyMessage() {
        DeadLetterMessageService deadLetterMessageService = mock(DeadLetterMessageService.class);
        KafkaAsyncCreateOrderDeadLetterConsumer consumer = new KafkaAsyncCreateOrderDeadLetterConsumer(
                deadLetterMessageService,
                new AsyncOrderSubmitProperties(),
                new AsyncOrderPartitionService()
        );

        consumer.consume(null);

        verify(deadLetterMessageService, never()).recordAsyncCreateOrderDeadLetter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }
}
