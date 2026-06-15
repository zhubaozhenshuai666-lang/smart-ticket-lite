package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "kafka")
public class KafkaAsyncCreateOrderDeadLetterConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAsyncCreateOrderDeadLetterConsumer.class);

    private final DeadLetterMessageService deadLetterMessageService;

    private final AsyncOrderSubmitProperties asyncOrderSubmitProperties;

    private final AsyncOrderPartitionService asyncOrderPartitionService;

    public KafkaAsyncCreateOrderDeadLetterConsumer(DeadLetterMessageService deadLetterMessageService,
                                                   AsyncOrderSubmitProperties asyncOrderSubmitProperties,
                                                   AsyncOrderPartitionService asyncOrderPartitionService) {
        this.deadLetterMessageService = deadLetterMessageService;
        this.asyncOrderSubmitProperties = asyncOrderSubmitProperties;
        this.asyncOrderPartitionService = asyncOrderPartitionService;
    }

    @KafkaListener(
            topics = "#{@asyncOrderSubmitProperties.kafkaAsyncCreateOrderDeadLetterTopic}",
            groupId = "#{@asyncOrderSubmitProperties.kafkaAsyncCreateOrderConsumerGroup + '-dlt'}"
    )
    public void consume(AsyncCreateOrderMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty Kafka async create order DLT message");
            return;
        }
        String messageId = message.getMessageId() == null || message.getMessageId().isBlank()
                ? "MSG" + message.getRequestId()
                : message.getMessageId();
        LOGGER.warn("Recorded Kafka async create order DLT message, requestId={}, messageId={}",
                message.getRequestId(), messageId);
        deadLetterMessageService.recordAsyncCreateOrderDeadLetter(
                message,
                asyncOrderSubmitProperties.getKafkaAsyncCreateOrderDeadLetterTopic(),
                asyncOrderSubmitProperties.getKafkaAsyncCreateOrderTopic(),
                asyncOrderPartitionService.partitionKey(message),
                messageId,
                ConsumerExceptionTypeEnum.UNKNOWN_ERROR,
                "Kafka异步创单消费重试耗尽，已进入DLT"
        );
    }
}
