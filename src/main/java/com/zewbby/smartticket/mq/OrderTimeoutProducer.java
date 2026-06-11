package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.task.LocalMessagePublishTask;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OrderTimeoutProducer {

    private final LocalMessageService localMessageService;

    private final LocalMessagePublishTask localMessagePublishTask;

    private final OrderTimeoutProperties orderTimeoutProperties;

    public OrderTimeoutProducer(LocalMessageService localMessageService,
                                LocalMessagePublishTask localMessagePublishTask,
                                OrderTimeoutProperties orderTimeoutProperties) {
        this.localMessageService = localMessageService;
        this.localMessagePublishTask = localMessagePublishTask;
        this.orderTimeoutProperties = orderTimeoutProperties;
    }

    /**
     * 提交订单超时关闭消息。
     *
     * 这里故意不直接调用 RabbitTemplate。订单超时关闭和异步创单一样，都是交易主链路的一部分：
     * 如果订单创建成功但延迟消息丢了，locked_stock 会一直占住，未支付 payment_order 也无法关闭。
     * Outbox 让“需要发送超时关闭消息”先落库，再由统一发送器投递并等待 Publisher Confirm。
     */
    public String sendOrderTimeoutMessage(OrderTimeoutMessage message) {
        if (!orderTimeoutProperties.isDelayMessageEnabled()) {
            return null;
        }
        String messageId = localMessageService.createOrderTimeoutCloseMessage(message);
        publishAfterCommit(messageId);
        return messageId;
    }

    public String sendOrderTimeoutMessage(Long orderId, String orderNo) {
        return sendOrderTimeoutMessage(new OrderTimeoutMessage(orderId, orderNo));
    }

    private void publishAfterCommit(String messageId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            localMessagePublishTask.publishByMessageId(messageId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                localMessagePublishTask.publishByMessageId(messageId);
            }
        });
    }
}
