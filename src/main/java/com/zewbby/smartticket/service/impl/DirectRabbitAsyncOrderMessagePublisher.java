package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "direct-rabbit")
public class DirectRabbitAsyncOrderMessagePublisher implements AsyncOrderMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    private final MqConsumerProperties mqConsumerProperties;

    private final AsyncOrderSubmitProperties asyncOrderSubmitProperties;

    private final AsyncOrderPartitionService asyncOrderPartitionService;

    public DirectRabbitAsyncOrderMessagePublisher(RabbitTemplate rabbitTemplate,
                                                  MqConsumerProperties mqConsumerProperties,
                                                  AsyncOrderSubmitProperties asyncOrderSubmitProperties) {
        this(rabbitTemplate, mqConsumerProperties, asyncOrderSubmitProperties, new AsyncOrderPartitionService());
    }

    public DirectRabbitAsyncOrderMessagePublisher(RabbitTemplate rabbitTemplate,
                                                  MqConsumerProperties mqConsumerProperties,
                                                  AsyncOrderSubmitProperties asyncOrderSubmitProperties,
                                                  AsyncOrderPartitionService asyncOrderPartitionService) {
        this.rabbitTemplate = rabbitTemplate;
        this.mqConsumerProperties = mqConsumerProperties;
        this.asyncOrderSubmitProperties = asyncOrderSubmitProperties;
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
        CorrelationData correlationData = new CorrelationData(messageId);
        MessagePostProcessor postProcessor = amqpMessage -> {
            amqpMessage.getMessageProperties().setCorrelationId(messageId);
            return amqpMessage;
        };
        rabbitTemplate.convertAndSend(
                RabbitMqConstant.ORDER_ASYNC_EXCHANGE,
                routingKey(message),
                message,
                postProcessor,
                correlationData
        );
        waitForConfirmIfNecessary(correlationData);
        return messageId;
    }

    private void waitForConfirmIfNecessary(CorrelationData correlationData) {
        if (!asyncOrderSubmitProperties.isDirectRabbitWaitForConfirm()) {
            return;
        }
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    asyncOrderSubmitProperties.getDirectRabbitConfirmTimeoutMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm == null ? "confirm timeout" : confirm.getReason();
                throw new BusinessException("异步下单消息发布失败: " + reason);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("异步下单消息发布确认失败: " + exception.getMessage());
        }
    }

    private String routingKey(AsyncCreateOrderMessage message) {
        int shardCount = mqConsumerProperties.getAsyncQueueShardCount();
        if (shardCount <= 1) {
            return RabbitMqConstant.ORDER_ASYNC_ROUTING_KEY;
        }
        int shardNo = asyncOrderPartitionService.partition(message, shardCount);
        return RabbitMqConstant.orderAsyncRoutingKey(shardNo);
    }
}
