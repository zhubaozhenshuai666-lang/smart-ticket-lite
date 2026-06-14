package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.LocalMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitPublisherCallbackHandler implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitPublisherCallbackHandler.class);

    private final LocalMessageService localMessageService;

    public RabbitPublisherCallbackHandler(LocalMessageService localMessageService) {
        this.localMessageService = localMessageService;
    }

    /**
     * Publisher Confirm 回调。
     *
     * ack=true 只代表 RabbitMQ Broker 已经确认收到发布的消息，不代表消费者已经处理成功。
     * 消费者是否创建订单成功，仍然要看 ticket_order_request.status。
     *
     * CorrelationData.id 必须稳定等于 local_message.message_id。这样回调线程不需要解析业务 payload，
     * 就能精确找到本地消息记录。不能在 convertAndSend 后立即标记 CONFIRMED，因为那时 Broker 可能还没收到消息。
     *
     * 当前项目选择在回调线程里做一次轻量 DB 状态更新，避免额外线程池复杂度。
     * 这不是强可靠的唯一依赖：如果应用在回调前宕机或 DB 更新失败，Confirm 超时扫描会把 SENT/SENDING 重新置为 FAILED 兜底。
     */
    @Override
    //这三个参数是RabbitMQ 底层网络线程在收到 Broker（服务端）响应后，回调该方法时自动塞进去的
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        /*
        安全提取messageId   why三元？ -> 极高并发下，如果发送消息时因为代码 bug 漏传了 CorrelationData，
        回调依然会被触发。如果不加这层校验，直接拿 null 去查数据库，会引发空指针或全表扫描。
        这里的 return 叫“尽早失败（Fail-Fast）”，记录一条警告日志后立刻终止，保护后续的数据库资源。
         */
        String messageId = correlationData == null ? null : correlationData.getId();
        if (messageId == null || messageId.isBlank()) {
            LOGGER.warn("Received RabbitMQ confirm without correlation messageId, ack={}, cause={}", ack, cause);
            return;
        }

        try {
            //成功被 Broker 接收
            if (ack) {
                //标记为已接受
                localMessageService.markConfirmed(messageId);
                LOGGER.info("RabbitMQ broker confirmed local message, messageId={}", messageId);
                return;
            }
            //被 Broker 明确拒绝
            localMessageService.markPublishFailedByMessageId(messageId, "Broker nack: " + safeCause(cause));
            LOGGER.warn("RabbitMQ broker nacked local message, messageId={}, cause={}", messageId, cause);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to handle RabbitMQ confirm, messageId={}, ack={}", messageId, ack, exception);
        }
    }

    /**
     * ReturnCallback / ReturnsCallback 回调。
     *
     * 当 exchange 存在但 routingKey 找不到队列，且 mandatory=true 时，Broker 会把消息退回给生产者。
     * 这表示消息没有进入目标队列，必须标记 FAILED 等待重试或人工处理。
     * Return 不是消费者失败，它发生在路由阶段；Confirm ack 仍可能随后到达，所以 DB 更新必须带状态条件避免互相覆盖。
     */
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        String messageId = returned.getMessage().getMessageProperties().getCorrelationId();
        if (messageId == null || messageId.isBlank()) {
            LOGGER.warn("Received returned message without correlation id, exchange={}, routingKey={}, replyCode={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText());
            return;
        }
        String reason = "Message returned: replyCode=" + returned.getReplyCode()
                + ", replyText=" + returned.getReplyText()
                + ", exchange=" + returned.getExchange()
                + ", routingKey=" + returned.getRoutingKey();
        try {
            localMessageService.markReturnedByMessageId(messageId, reason);
            LOGGER.warn("RabbitMQ returned local message, messageId={}, reason={}", messageId, reason);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to handle returned RabbitMQ message, messageId={}", messageId, exception);
        }
    }

    private String safeCause(String cause) {
        return cause == null || cause.isBlank() ? "unknown" : cause;
    }
}
