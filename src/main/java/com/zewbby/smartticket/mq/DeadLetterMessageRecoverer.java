package com.zewbby.smartticket.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.enums.LocalMessageBusinessTypeEnum;
import com.zewbby.smartticket.service.DeadLetterMessageService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class DeadLetterMessageRecoverer implements MessageRecoverer {

    private final DeadLetterMessageService deadLetterMessageService;

    private final ObjectMapper objectMapper;

    public DeadLetterMessageRecoverer(DeadLetterMessageService deadLetterMessageService,
                                      ObjectMapper objectMapper) {
        this.deadLetterMessageService = deadLetterMessageService;
        this.objectMapper = objectMapper;
    }

    /**
     * 当 Spring AMQP 框架内部的重试机制（比如配置了最多重试 3 次）全部执行完毕且依然失败后，
     * 系统会彻底放弃消费，并自动回调这个方法进行最后的“收尸”处理。
     * @param message the message to recover
     * @param cause the cause of the error
     */
    @Override
    public void recover(Message message, Throwable cause) {
        //提取路由键、交换机等元数据。
        MessageProperties properties = message.getMessageProperties();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        ConsumerExceptionTypeEnum exceptionType = resolveExceptionType(cause);

        //反序列化
        try {
            AsyncCreateOrderMessage asyncMessage = objectMapper.readValue(message.getBody(), AsyncCreateOrderMessage.class);
            deadLetterMessageService.recordAsyncCreateOrderDeadLetter(
                    asyncMessage,
                    properties.getConsumerQueue(),
                    properties.getReceivedExchange(),
                    properties.getReceivedRoutingKey(),
                    resolveMessageId(properties),
                    exceptionType,
                    rootMessage(cause)
            );
        }
        /**
         * 假设生产者发来的数据格式全错，反序列化必然抛出异常。如果不捕获这个 IOException，recover 方法本身就会崩溃，
         * 导致这条死信连记入数据库的机会都没有，直接人间蒸发。
         * 这里的 Catch 分支使用纯文本字符串（payload）强行兜底落库，保留了最原始的错误现场。
         */
        catch (IOException parseException) {
            deadLetterMessageService.recordDeadLetter(
                    resolveMessageId(properties),
                    LocalMessageBusinessTypeEnum.ASYNC_CREATE_ORDER.getCode(),
                    "UNKNOWN",
                    properties.getConsumerQueue() == null ? RabbitMqConstant.ORDER_ASYNC_QUEUE : properties.getConsumerQueue(),
                    properties.getReceivedExchange(),
                    properties.getReceivedRoutingKey(),
                    payload,
                    ConsumerExceptionTypeEnum.DATA_INCONSISTENCY,
                    "消息反序列化失败: " + parseException.getMessage()
            );
        }
    }

    private ConsumerExceptionTypeEnum resolveExceptionType(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof ConsumerRetryableException retryableException) {
                return retryableException.getExceptionType();
            }
            current = current.getCause();
        }
        return ConsumerExceptionTypeEnum.UNKNOWN_ERROR;
    }

    /**
     * 找到最底层的真实死因
     * 架构中的作用：提升自动化补偿与排障效率。
     * @param properties
     * @return
     */
    private String resolveMessageId(MessageProperties properties) {
        if (properties.getMessageId() != null && !properties.getMessageId().isBlank()) {
            return properties.getMessageId();
        }
        return properties.getCorrelationId();
    }
    private String rootMessage(Throwable cause) {
        Throwable current = cause;
        Throwable root = cause;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        return root == null ? "消费失败" : root.getMessage();
    }
}
