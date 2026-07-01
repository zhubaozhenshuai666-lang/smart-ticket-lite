package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.mq.OrderTimeoutMessage;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.service.OrderTimeoutMessagePublisher;
import com.zewbby.smartticket.task.LocalMessagePublishTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.order-timeout", name = "publisher-mode", havingValue = "outbox")
public class OutboxOrderTimeoutMessagePublisher implements OrderTimeoutMessagePublisher {

    private final LocalMessageService localMessageService;

    private final LocalMessagePublishTask localMessagePublishTask;

    public OutboxOrderTimeoutMessagePublisher(LocalMessageService localMessageService,
                                              LocalMessagePublishTask localMessagePublishTask) {
        this.localMessageService = localMessageService;
        this.localMessagePublishTask = localMessagePublishTask;
    }

    @Override
    public String publish(OrderTimeoutMessage message) {
        String messageId = localMessageService.createOrderTimeoutCloseMessage(message);
        publishAfterCommit(messageId);
        return messageId;
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
