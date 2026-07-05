package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.aop.MqConsumeTrace;
import com.zewbby.smartticket.config.MqConsumerProperties;
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
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${smart-ticket.async-order-submit.rocket-mq-async-create-order-topic}",
        consumerGroup = "${smart-ticket.async-order-submit.rocket-mq-async-create-order-consumer-group}",
        consumeMode = ConsumeMode.ORDERLY,
        messageModel = MessageModel.CLUSTERING,
        consumeThreadNumber = 24,
        consumeThreadMax = 96,
        maxReconsumeTimes = 3
)
public class RocketMqAsyncCreateOrderConsumer implements RocketMQListener<AsyncCreateOrderMessage>,
        RocketMQPushConsumerLifecycleListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqAsyncCreateOrderConsumer.class);

    private final AsyncCreateOrderBatchDispatcher asyncCreateOrderBatchDispatcher;

    private final MqConsumerProperties mqConsumerProperties;

    public RocketMqAsyncCreateOrderConsumer(AsyncCreateOrderConsumer asyncCreateOrderConsumer) {
        this(new AsyncCreateOrderBatchDispatcher(asyncCreateOrderConsumer), new MqConsumerProperties());
    }

    @Autowired
    public RocketMqAsyncCreateOrderConsumer(AsyncCreateOrderBatchDispatcher asyncCreateOrderBatchDispatcher,
                                            MqConsumerProperties mqConsumerProperties) {
        this.asyncCreateOrderBatchDispatcher = asyncCreateOrderBatchDispatcher;
        this.mqConsumerProperties = mqConsumerProperties;
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        RocketMqConsumerTuningSupport.apply(consumer, mqConsumerProperties);
    }

    @Override
    @MqConsumeTrace(
            topic = "async-create-order",
            consumerGroup = "rocketmq-async-create-order",
            messageId = "#p0?.messageId",
            businessKey = "#p0?.requestId"
    )
    public void onMessage(AsyncCreateOrderMessage message) {
        if (message == null) {
            LOGGER.warn("Ignored empty RocketMQ async create order message");
            return;
        }
        LOGGER.info("Received RocketMQ async create order message, requestId={}, messageId={}",
                message.getRequestId(), message.getMessageId());
        asyncCreateOrderBatchDispatcher.consume(message);
    }
}
