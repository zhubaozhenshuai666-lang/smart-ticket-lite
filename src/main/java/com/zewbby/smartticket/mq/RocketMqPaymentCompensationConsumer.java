package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.PaymentService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.payment-compensation", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${smart-ticket.payment-compensation.rocket-mq-topic}",
        consumerGroup = "${smart-ticket.payment-compensation.rocket-mq-consumer-group}"
)
public class RocketMqPaymentCompensationConsumer implements RocketMQListener<PaymentCompensationMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqPaymentCompensationConsumer.class);

    private final PaymentService paymentService;

    public RocketMqPaymentCompensationConsumer(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Override
    public void onMessage(PaymentCompensationMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty RocketMQ payment compensation message");
            return;
        }
        LOGGER.info("Received RocketMQ payment compensation message, paymentNo={}, orderId={}, messageId={}",
                message.getPaymentNo(), message.getOrderId(), message.getMessageId());
        paymentService.compensateMockPay(
                message.getPaymentNo(),
                message.getUserId(),
                Boolean.TRUE.equals(message.getSuccess())
        );
    }
}
