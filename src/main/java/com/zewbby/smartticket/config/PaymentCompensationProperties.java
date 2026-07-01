package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.payment-compensation")
public class PaymentCompensationProperties {

    private boolean enabled = true;

    private String rocketMqTopic = "smart-ticket.payment.compensation";

    private String rocketMqConsumerGroup = "smart-ticket-payment-compensation";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRocketMqTopic() {
        return rocketMqTopic == null || rocketMqTopic.isBlank()
                ? "smart-ticket.payment.compensation"
                : rocketMqTopic.trim();
    }

    public void setRocketMqTopic(String rocketMqTopic) {
        this.rocketMqTopic = rocketMqTopic;
    }

    public String getRocketMqConsumerGroup() {
        return rocketMqConsumerGroup == null || rocketMqConsumerGroup.isBlank()
                ? "smart-ticket-payment-compensation"
                : rocketMqConsumerGroup.trim();
    }

    public void setRocketMqConsumerGroup(String rocketMqConsumerGroup) {
        this.rocketMqConsumerGroup = rocketMqConsumerGroup;
    }
}
