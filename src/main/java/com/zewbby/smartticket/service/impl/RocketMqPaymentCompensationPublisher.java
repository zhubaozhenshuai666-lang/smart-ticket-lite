package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.PaymentCompensationProperties;
import com.zewbby.smartticket.mq.PaymentCompensationMessage;
import com.zewbby.smartticket.service.PaymentCompensationPublisher;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.payment-compensation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RocketMqPaymentCompensationPublisher implements PaymentCompensationPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    private final PaymentCompensationProperties properties;

    public RocketMqPaymentCompensationPublisher(RocketMQTemplate rocketMQTemplate,
                                                PaymentCompensationProperties properties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
    }

    @Override
    public String publish(PaymentCompensationMessage message) {
        String messageId = message.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            messageId = "PC" + UUID.randomUUID().toString().replace("-", "");
            message.setMessageId(messageId);
        }
        rocketMQTemplate.syncSendOrderly(
                properties.getRocketMqTopic(),
                message,
                paymentKey(message)
        );
        return messageId;
    }

    private String paymentKey(PaymentCompensationMessage message) {
        if (message == null || message.getPaymentNo() == null || message.getPaymentNo().isBlank()) {
            return "payment:unknown";
        }
        return "payment:" + message.getPaymentNo();
    }
}
