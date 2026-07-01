package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.service.OrderTimeoutMessagePublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.order-timeout", name = "publisher-mode", havingValue = "rocketmq")
public class RocketMqOrderTimeoutMessagePublisher implements OrderTimeoutMessagePublisher {

    private final RocketMQTemplate rocketMQTemplate;

    private final OrderTimeoutProperties orderTimeoutProperties;

    public RocketMqOrderTimeoutMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                                OrderTimeoutProperties orderTimeoutProperties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.orderTimeoutProperties = orderTimeoutProperties;
    }

    @Override
    public String publish(OrderTimeoutMessage message) {
        String messageId = ensureMessageId(message);
        publishAfterCommit(message);
        return messageId;
    }

    private void publishAfterCommit(OrderTimeoutMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishRocketMq(message);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishRocketMq(message);
            }
        });
    }

    private void publishRocketMq(OrderTimeoutMessage message) {
        Message<OrderTimeoutMessage> rocketMessage = MessageBuilder
                .withPayload(message)
                .setHeader(RocketMQHeaders.KEYS, orderTimeoutKey(message))
                .build();
        rocketMQTemplate.syncSend(
                orderTimeoutProperties.getRocketMqOrderTimeoutTopic(),
                rocketMessage,
                orderTimeoutProperties.getRocketMqSendTimeoutMillis(),
                orderTimeoutProperties.getRocketMqDelayLevel()
        );
    }

    private String ensureMessageId(OrderTimeoutMessage message) {
        if (message.getMessageId() == null || message.getMessageId().isBlank()) {
            message.setMessageId("OT" + UUID.randomUUID().toString().replace("-", ""));
        }
        return message.getMessageId();
    }

    private String orderTimeoutKey(OrderTimeoutMessage message) {
        if (message == null || message.getOrderId() == null) {
            return "order:unknown";
        }
        return "order:" + message.getOrderId();
    }
}
