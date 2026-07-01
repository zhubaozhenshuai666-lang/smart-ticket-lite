package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "rocketmq")
public class RocketMqAsyncOrderMessagePublisher implements AsyncOrderMessagePublisher {

    private final RocketMQTemplate rocketMQTemplate;

    private final AsyncOrderSubmitProperties properties;

    private final AsyncOrderPartitionService asyncOrderPartitionService;

    @Autowired
    public RocketMqAsyncOrderMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                              AsyncOrderSubmitProperties properties) {
        this(rocketMQTemplate, properties, new AsyncOrderPartitionService());
    }

    RocketMqAsyncOrderMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                       AsyncOrderSubmitProperties properties,
                                       AsyncOrderPartitionService asyncOrderPartitionService) {
        this.rocketMQTemplate = rocketMQTemplate;
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
        rocketMQTemplate.syncSendOrderly(
                properties.getRocketMqAsyncCreateOrderTopic(),
                message,
                asyncOrderPartitionService.partitionKey(message)
        );
        return messageId;
    }
}
