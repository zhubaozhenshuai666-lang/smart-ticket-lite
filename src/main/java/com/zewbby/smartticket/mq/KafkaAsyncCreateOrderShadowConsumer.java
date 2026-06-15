package com.zewbby.smartticket.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "smart-ticket.async-order-submit",
        name = "kafka-async-create-order-consumer-enabled",
        havingValue = "true"
)
public class KafkaAsyncCreateOrderShadowConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAsyncCreateOrderShadowConsumer.class);

    @KafkaListener(
            topics = "#{@asyncOrderSubmitProperties.kafkaAsyncCreateOrderTopic}",
            groupId = "#{@asyncOrderSubmitProperties.kafkaAsyncCreateOrderConsumerGroup}"
    )
    public void consume(AsyncCreateOrderMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty Kafka async create order message");
            return;
        }
        LOGGER.info("Received Kafka async create order shadow message, requestId={}, messageId={}",
                message.getRequestId(), message.getMessageId());
    }
}
