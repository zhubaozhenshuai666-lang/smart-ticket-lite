package com.zewbby.smartticket.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "kafka")
public class KafkaAsyncCreateOrderConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaAsyncCreateOrderConsumer.class);

    private final AsyncCreateOrderConsumer asyncCreateOrderConsumer;

    public KafkaAsyncCreateOrderConsumer(AsyncCreateOrderConsumer asyncCreateOrderConsumer) {
        this.asyncCreateOrderConsumer = asyncCreateOrderConsumer;
    }

    @KafkaListener(
            topics = "#{@asyncOrderSubmitProperties.kafkaAsyncCreateOrderTopic}",
            groupId = "#{@asyncOrderSubmitProperties.kafkaAsyncCreateOrderConsumerGroup}",
            containerFactory = "asyncOrderKafkaListenerContainerFactory"
    )
    public void consume(AsyncCreateOrderMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty Kafka async create order message");
            return;
        }
        LOGGER.info("Received Kafka async create order message, requestId={}, messageId={}",
                message.getRequestId(), message.getMessageId());
        asyncCreateOrderConsumer.consume(message);
    }
}
