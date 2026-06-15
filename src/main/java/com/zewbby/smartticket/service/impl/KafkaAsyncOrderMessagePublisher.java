package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "kafka")
public class KafkaAsyncOrderMessagePublisher implements AsyncOrderMessagePublisher {

    private final KafkaTemplate<String, AsyncCreateOrderMessage> kafkaTemplate;

    private final AsyncOrderSubmitProperties properties;

    private final AsyncOrderPartitionService asyncOrderPartitionService;

    public KafkaAsyncOrderMessagePublisher(KafkaTemplate<String, AsyncCreateOrderMessage> kafkaTemplate,
                                           AsyncOrderSubmitProperties properties) {
        this(kafkaTemplate, properties, new AsyncOrderPartitionService());
    }

    KafkaAsyncOrderMessagePublisher(KafkaTemplate<String, AsyncCreateOrderMessage> kafkaTemplate,
                                    AsyncOrderSubmitProperties properties,
                                    AsyncOrderPartitionService asyncOrderPartitionService) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.asyncOrderPartitionService = asyncOrderPartitionService;
    }

    @Override
    public String publish(AsyncCreateOrderMessage message) {
        String messageId = message.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            messageId = "MSG" + message.getRequestId();
        }
        return publish(messageId, message);
    }

    @Override
    public String publish(String messageId, AsyncCreateOrderMessage message) {
        message.setMessageId(messageId);
        kafkaTemplate.send(
                properties.getKafkaAsyncCreateOrderTopic(),
                asyncOrderPartitionService.partitionKey(message),
                message
        );
        return messageId;
    }
}
