package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.domain-event")
public class DomainEventProperties {

    private boolean enabled = true;

    private String orderCreatedTopic = "smart-ticket.event.order-created";

    private String paymentPaidTopic = "smart-ticket.event.payment-paid";

    private String stockChangedTopic = "smart-ticket.event.stock-changed";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getOrderCreatedTopic() {
        return orderCreatedTopic == null || orderCreatedTopic.isBlank()
                ? "smart-ticket.event.order-created"
                : orderCreatedTopic.trim();
    }

    public void setOrderCreatedTopic(String orderCreatedTopic) {
        this.orderCreatedTopic = orderCreatedTopic;
    }

    public String getPaymentPaidTopic() {
        return paymentPaidTopic == null || paymentPaidTopic.isBlank()
                ? "smart-ticket.event.payment-paid"
                : paymentPaidTopic.trim();
    }

    public void setPaymentPaidTopic(String paymentPaidTopic) {
        this.paymentPaidTopic = paymentPaidTopic;
    }

    public String getStockChangedTopic() {
        return stockChangedTopic == null || stockChangedTopic.isBlank()
                ? "smart-ticket.event.stock-changed"
                : stockChangedTopic.trim();
    }

    public void setStockChangedTopic(String stockChangedTopic) {
        this.stockChangedTopic = stockChangedTopic;
    }
}
