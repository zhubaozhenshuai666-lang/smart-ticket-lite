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
     * 接收一个超时消息对象，先把它安全地存到本地数据库的消息表里，然后准备发送。
     * @param message
     * @return
     */
    public String sendOrderTimeoutMessage(OrderTimeoutMessage message) {
        //如果不开启延迟功能直接拦截
        if (!orderTimeoutProperties.isDelayMessageEnabled()) {
            return null;
        }
        //在本地数据库插入一条消息记录，状态为 SENDING（发送中），并返回这笔消息的唯一身份证 messageId
        String messageId = localMessageService.createOrderTimeoutCloseMessage(message);
        publishAfterCommit(messageId);
        return messageId;
    }

    public String sendOrderTimeoutMessage(Long orderId, String orderNo) {
        return sendOrderTimeoutMessage(new OrderTimeoutMessage(orderId, orderNo));
    }

    /**
     * 确保只有在当前的数据库事务成功提交（Commit）之后，才真正把消息通过网络送给 RabbitMQ。
     * @param messageId
     */
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
