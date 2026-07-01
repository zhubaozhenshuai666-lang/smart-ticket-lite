package com.zewbby.smartticket.mq;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${smart-ticket.async-order-submit.rocket-mq-async-create-order-topic}",
        consumerGroup = "${smart-ticket.async-order-submit.rocket-mq-async-create-order-consumer-group}"
)
public class RocketMqAsyncCreateOrderConsumer implements RocketMQListener<AsyncCreateOrderMessage> {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqAsyncCreateOrderConsumer.class);

    private final AsyncCreateOrderConsumer asyncCreateOrderConsumer;

    public RocketMqAsyncCreateOrderConsumer(AsyncCreateOrderConsumer asyncCreateOrderConsumer) {
        this.asyncCreateOrderConsumer = asyncCreateOrderConsumer;
    }

    @Override
    public void onMessage(AsyncCreateOrderMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty RocketMQ async create order message");
            return;
        }
        LOGGER.info("Received RocketMQ async create order message, requestId={}, messageId={}",
                message.getRequestId(), message.getMessageId());
        asyncCreateOrderConsumer.consume(message);
    }
}
