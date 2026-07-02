package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.service.PaymentService;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.payment-compensation", name = "enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${smart-ticket.payment-compensation.rocket-mq-topic}",
        consumerGroup = "${smart-ticket.payment-compensation.rocket-mq-consumer-group}",
        consumeMode = ConsumeMode.ORDERLY,
        messageModel = MessageModel.CLUSTERING,
        consumeThreadNumber = 24,
        consumeThreadMax = 96,
        maxReconsumeTimes = 3
)
public class RocketMqPaymentCompensationConsumer implements RocketMQListener<PaymentCompensationMessage>,
        RocketMQPushConsumerLifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqPaymentCompensationConsumer.class);

    private final PaymentService paymentService;

    private final MqConsumerProperties mqConsumerProperties;

    public RocketMqPaymentCompensationConsumer(PaymentService paymentService) {
        this(paymentService, new MqConsumerProperties());
    }

    @Autowired
    public RocketMqPaymentCompensationConsumer(PaymentService paymentService,
                                               MqConsumerProperties mqConsumerProperties) {
        this.paymentService = paymentService;
        this.mqConsumerProperties = mqConsumerProperties;
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        RocketMqConsumerTuningSupport.apply(consumer, mqConsumerProperties);
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
