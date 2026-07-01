package com.zewbby.smartticket.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.LocalMessageProperties;
import com.zewbby.smartticket.domain.entity.LocalMessage;
import com.zewbby.smartticket.domain.event.OrderCreatedEvent;
import com.zewbby.smartticket.domain.event.PaymentPaidEvent;
import com.zewbby.smartticket.domain.event.StockChangedEvent;
import com.zewbby.smartticket.enums.LocalMessageBusinessTypeEnum;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.service.LocalMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class LocalMessagePublishTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalMessagePublishTask.class);

    private final LocalMessageService localMessageService;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    private final ObjectMapper objectMapper;

    private final LocalMessageProperties localMessageProperties;

    public LocalMessagePublishTask(LocalMessageService localMessageService,
                                   KafkaTemplate<String, Object> kafkaTemplate,
                                   ObjectMapper objectMapper,
                                   LocalMessageProperties localMessageProperties) {
        this.localMessageService = localMessageService;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.localMessageProperties = localMessageProperties;
    }

    /**
     * 扫描 INIT / FAILED 消息并投递到 Kafka。
     *
     * INIT 表示业务事务已经提交了“必须发送这条消息”的意图；FAILED 表示上一次发送、nack、return 或 confirm timeout 后可以重试。
     * retry_count、max_retry_count、next_retry_time 控制重试次数和退避时间，避免 Kafka 不可用时无限打爆数据库和 MQ。
     *
     * 多实例部署时，多个发送器可能扫描到同一批消息。真正的并发保护不靠 Java if，而靠 tryMarkSending 的 SQL 条件更新：
     * 只有抢到 SENDING 的实例才允许发送，抢占失败说明别的实例已经在处理。
     */
    @Scheduled(fixedDelay = 3000)
    public void publishPendingMessages() {
        // 开关校验
        if (!localMessageProperties.isSenderEnabled()) {
            return;
        }
        // 捞出那些到达了执行时间、状态为 INIT 或 FAILED 的消息
        List<LocalMessage> messages = localMessageService.selectPublishableMessages(
                LocalDateTime.now(),
                localMessageProperties.getBatchSize()
        );
        for (LocalMessage message : messages) {
            // 查数据库取对应的message 用行锁实现去重
            if (!localMessageService.tryMarkSending(message)) {
                LOGGER.debug("Skipped local message because another sender claimed it, messageId={}",
                        message.getMessageId());
                continue;
            }
            //发送消息
            publishOne(message);
        }
    }

    /**
     * 不依靠定时任务，而是由业务线程在事务提交后（afterCommit）立即强行触发的即时发送。
     * 架构中的作用：降低消息延迟。如果全靠 3 秒一次的定时任务，用户下完单平均要等 1.5 秒消息才发出去。
     * 通过这个方法，在事务成功后立刻投递，99.9% 的消息在毫秒级就进入了 Kafka。定时任务只负责捞取那 0.1% 失败的残余。
     * @param messageId
     */
    public void publishByMessageId(String messageId) {
        if (!localMessageProperties.isSenderEnabled()) {
            return;
        }
        LocalMessage message = localMessageService.getByMessageId(messageId);
        if (message == null) {
            LOGGER.warn("Skipped immediate local message publish because message does not exist, messageId={}",
                    messageId);
            return;
        }
        //状态抢占，确保安全性
        if (!localMessageService.tryMarkSending(message)) {
            LOGGER.debug("Skipped immediate local message publish because message is not claimable, messageId={}",
                    messageId);
            return;
        }
        publishOne(message);
    }

    /**
     * 发送一条本地消息。
     *
     * KafkaTemplate.send 返回不抛异常，只能说明客户端调用链路没有立刻失败，不能说明 Broker 已经确认收到消息。
     * 为了降低 Outbox 写放大，默认不再强制写 SENT 中间态；消息保持 SENDING，直到 Kafka send callback 直接改 CONFIRMED。
     * Confirm 超时扫描覆盖 SENDING/SENT 两种状态，所以发送线程退出、回调丢失或应用重启仍能重新置 FAILED 后重试。
     * 对订单超时关闭也是同一规则：CONFIRMED 只代表 Broker 收到延迟消息，不代表订单已经被关闭；
     * 真正关闭成功与否必须看超时消费者读取数据库订单状态后的幂等处理结果。
     */

    /**
     * 调用 Spring Kafka 发送消息。
     * 架构中的作用：将本地消息表的抽象数据，精准对接到 Kafka topic 和分区 key 上。
     * @param localMessage
     */
    void publishOne(LocalMessage localMessage) {
        try {
            Object payload = toPayload(localMessage);
            kafkaTemplate.send(
                    localMessage.getExchangeName(),
                    localMessage.getRoutingKey(),
                    payload
            ).whenComplete((result, exception) -> {
                if (exception == null) {
                    localMessageService.markConfirmed(localMessage.getMessageId());
                    LOGGER.info("Kafka confirmed local message, messageId={}, businessKey={}",
                            localMessage.getMessageId(), localMessage.getBusinessKey());
                    return;
                }
                localMessageService.markPublishFailed(localMessage, exception.getMessage());
                LOGGER.warn("Kafka failed to send local message, messageId={}, businessKey={}",
                        localMessage.getMessageId(), localMessage.getBusinessKey(), exception);
            });
            if (localMessageProperties.isMarkSentEnabled()) {
                localMessageService.markSent(localMessage.getId());
            }
            LOGGER.info("Submitted local message to Kafka, messageId={}, businessKey={}",
                    localMessage.getMessageId(), localMessage.getBusinessKey());
        } catch (Exception exception) {
            localMessageService.markPublishFailed(localMessage, exception.getMessage());
            LOGGER.warn("Failed to send local message, messageId={}, businessKey={}",
                    localMessage.getMessageId(), localMessage.getBusinessKey(), exception);
        }
    }

    /**
     * 扫描长时间没有 Confirm 的消息。
     *
     * 消息可能卡在 SENDING/SENT：例如发送线程宕机、Kafka callback 没执行、回调 DB 更新失败，或者应用在回调前退出。
     * 不能让这些消息永久停留在“等待确认”，所以使用 updated_at 做超时判断，转回 FAILED 等待重试。
     * schema 中对应索引是 idx_status_updated_at(status, updated_at)。
     */
    @Scheduled(fixedDelay = 10000)
    public void scanConfirmTimeoutMessages() {
        //开关校验
        if (!localMessageProperties.isSenderEnabled()) {
            return;
        }
        //计算超时截止线
        LocalDateTime timeoutBefore = LocalDateTime.now()
                .minusSeconds(localMessageProperties.getConfirmTimeoutSeconds());

        //查找出batchSize规模的超时message
        List<LocalMessage> messages = localMessageService.selectConfirmTimeoutMessages(
                timeoutBefore,
                localMessageProperties.getBatchSize()
        );
        //把这一批超时message全部标记失败
        for (LocalMessage message : messages) {
            localMessageService.markPublishFailed(message, "Kafka发送确认超时");
        }
    }

    /**
     * 反序列化工具：发送时，需要根据业务类型（businessType），用 Jackson 的 objectMapper 把它还原成真正的 Java 对象（秒杀创单对象 或 超时关单对象）。
     * @param localMessage
     * @return
     * @throws Exception
     */
    private Object toPayload(LocalMessage localMessage) throws Exception {
        if (LocalMessageBusinessTypeEnum.ASYNC_CREATE_ORDER.getCode().equals(localMessage.getBusinessType())) {
            return objectMapper.readValue(localMessage.getPayload(), AsyncCreateOrderMessage.class);
        }
        if (LocalMessageBusinessTypeEnum.ORDER_TIMEOUT_CLOSE.getCode().equals(localMessage.getBusinessType())) {
            return objectMapper.readValue(localMessage.getPayload(), OrderTimeoutMessage.class);
        }
        if (LocalMessageBusinessTypeEnum.ORDER_CREATED_EVENT.getCode().equals(localMessage.getBusinessType())) {
            return objectMapper.readValue(localMessage.getPayload(), OrderCreatedEvent.class);
        }
        if (LocalMessageBusinessTypeEnum.PAYMENT_PAID_EVENT.getCode().equals(localMessage.getBusinessType())) {
            return objectMapper.readValue(localMessage.getPayload(), PaymentPaidEvent.class);
        }
        if (LocalMessageBusinessTypeEnum.STOCK_CHANGED_EVENT.getCode().equals(localMessage.getBusinessType())) {
            return objectMapper.readValue(localMessage.getPayload(), StockChangedEvent.class);
        }
        throw new IllegalArgumentException("不支持的本地消息业务类型：" + localMessage.getBusinessType());
    }
}
