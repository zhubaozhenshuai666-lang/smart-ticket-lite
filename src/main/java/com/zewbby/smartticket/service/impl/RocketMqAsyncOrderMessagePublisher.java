package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderPublishLocalTransaction;
import com.zewbby.smartticket.service.AsyncOrderPublishTransactionContext;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import com.zewbby.smartticket.service.AsyncOrderTransactionMarkerService;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RocketMQTransactionListener(corePoolSize = 8, maximumPoolSize = 32, blockingQueueSize = 50000)
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "rocketmq")
public class RocketMqAsyncOrderMessagePublisher implements AsyncOrderMessagePublisher, RocketMQLocalTransactionListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(RocketMqAsyncOrderMessagePublisher.class);

    private final RocketMQTemplate rocketMQTemplate;

    private final AsyncOrderSubmitProperties properties;

    private final AsyncOrderPartitionService asyncOrderPartitionService;

    private final AsyncOrderTransactionMarkerService transactionMarkerService;

    @Autowired
    public RocketMqAsyncOrderMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                              AsyncOrderSubmitProperties properties,
                                              AsyncOrderTransactionMarkerService transactionMarkerService) {
        this(rocketMQTemplate, properties, new AsyncOrderPartitionService(), transactionMarkerService);
    }

    RocketMqAsyncOrderMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                       AsyncOrderSubmitProperties properties) {
        this(rocketMQTemplate, properties, new AsyncOrderPartitionService(), null);
    }

    RocketMqAsyncOrderMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                       AsyncOrderSubmitProperties properties,
                                       AsyncOrderPartitionService asyncOrderPartitionService) {
        this(rocketMQTemplate, properties, asyncOrderPartitionService, null);
    }

    RocketMqAsyncOrderMessagePublisher(RocketMQTemplate rocketMQTemplate,
                                       AsyncOrderSubmitProperties properties,
                                       AsyncOrderPartitionService asyncOrderPartitionService,
                                       AsyncOrderTransactionMarkerService transactionMarkerService) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.properties = properties;
        this.asyncOrderPartitionService = asyncOrderPartitionService;
        this.transactionMarkerService = transactionMarkerService;
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

    @Override
    public String publishInTransaction(String messageId,
                                       AsyncCreateOrderMessage message,
                                       AsyncOrderPublishLocalTransaction localTransaction) {
        message.setMessageId(messageId);
        AsyncOrderPublishTransactionContext context =
                new AsyncOrderPublishTransactionContext(messageId, message, localTransaction);
        Message<AsyncCreateOrderMessage> rocketMessage = MessageBuilder.withPayload(message)
                .setHeader(RocketMQHeaders.KEYS, message.getRequestId())
                .build();
        TransactionSendResult sendResult = rocketMQTemplate.sendMessageInTransaction(
                properties.getRocketMqAsyncCreateOrderTopic(),
                rocketMessage,
                context
        );
        if (context.getFailure() != null) {
            throw context.getFailure();
        }
        if (sendResult == null) {
            throw new IllegalStateException("RocketMQ事务消息发送结果为空");
        }
        if (sendResult.getLocalTransactionState() == LocalTransactionState.ROLLBACK_MESSAGE) {
            throw new IllegalStateException("RocketMQ异步创单本地事务已回滚");
        }
        if (sendResult.getLocalTransactionState() == LocalTransactionState.UNKNOW) {
            throw new IllegalStateException("RocketMQ异步创单本地事务状态未知");
        }
        return messageId;
    }

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        if (!(arg instanceof AsyncOrderPublishTransactionContext context)) {
            LOGGER.error("Rejected RocketMQ async order transaction because arg type is invalid, arg={}", arg);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        try {
            context.executeLocalTransaction();
            LOGGER.info("Committed RocketMQ async order local transaction, requestId={}, messageId={}",
                    context.getMessage().getRequestId(), context.getMessageId());
            return RocketMQLocalTransactionState.COMMIT;
        } catch (RuntimeException exception) {
            context.setFailure(exception);
            LOGGER.warn("Rolled back RocketMQ async order local transaction, requestId={}, messageId={}",
                    context.getMessage().getRequestId(), context.getMessageId(), exception);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String requestId = resolveRequestId(message);
        if (requestId == null || requestId.isBlank()) {
            LOGGER.error("Cannot check RocketMQ async order transaction because requestId header is missing");
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        try {
            if (transactionMarkerService != null && transactionMarkerService.hasCommittedDeduction(requestId)) {
                LOGGER.info("Committed RocketMQ async order transaction by check, requestId={}", requestId);
                return RocketMQLocalTransactionState.COMMIT;
            }
            LOGGER.warn("Rolled back RocketMQ async order transaction by check because no deduction marker exists, requestId={}",
                    requestId);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (RuntimeException exception) {
            LOGGER.error("Cannot check RocketMQ async order transaction because Redis marker check failed, requestId={}",
                    requestId, exception);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    private String resolveRequestId(Message message) {
        if (message == null || message.getHeaders() == null) {
            return null;
        }
        Object value = message.getHeaders().get(RocketMQHeaders.KEYS);
        return value == null ? null : String.valueOf(value);
    }
}
