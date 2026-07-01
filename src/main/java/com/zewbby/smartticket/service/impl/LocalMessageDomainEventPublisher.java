package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.DomainEventProperties;
import com.zewbby.smartticket.domain.entity.PaymentOrder;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.domain.event.OrderCreatedEvent;
import com.zewbby.smartticket.domain.event.PaymentPaidEvent;
import com.zewbby.smartticket.domain.event.StockChangedEvent;
import com.zewbby.smartticket.enums.LocalMessageBusinessTypeEnum;
import com.zewbby.smartticket.service.DomainEventPublisher;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.task.LocalMessagePublishTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.domain-event", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LocalMessageDomainEventPublisher implements DomainEventPublisher {

    private static final String EVENT_TYPE_ORDER_CREATED = "ORDER_CREATED";

    private static final String EVENT_TYPE_PAYMENT_PAID = "PAYMENT_PAID";

    private static final String EVENT_TYPE_STOCK_CHANGED = "STOCK_CHANGED";

    private final LocalMessageService localMessageService;

    private final LocalMessagePublishTask localMessagePublishTask;

    private final DomainEventProperties properties;

    public LocalMessageDomainEventPublisher(LocalMessageService localMessageService,
                                            LocalMessagePublishTask localMessagePublishTask,
                                            DomainEventProperties properties) {
        this.localMessageService = localMessageService;
        this.localMessagePublishTask = localMessagePublishTask;
        this.properties = properties;
    }

    @Override
    public void publishOrderCreated(TicketOrder order) {
        if (order == null) {
            return;
        }
        OrderCreatedEvent event = new OrderCreatedEvent(
                eventId(),
                EVENT_TYPE_ORDER_CREATED,
                order.getId(),
                order.getOrderNo(),
                order.getUserId(),
                order.getShowId(),
                order.getSessionId(),
                order.getTicketCategoryId(),
                order.getQuantity(),
                order.getTotalAmount(),
                LocalDateTime.now()
        );
        String messageId = localMessageService.createDomainEventMessage(
                LocalMessageBusinessTypeEnum.ORDER_CREATED_EVENT.getCode(),
                String.valueOf(order.getId()),
                properties.getOrderCreatedTopic(),
                orderKey(order.getId()),
                event
        );
        publishAfterCommit(messageId);
    }

    @Override
    public void publishPaymentPaid(PaymentOrder paymentOrder) {
        if (paymentOrder == null) {
            return;
        }
        PaymentPaidEvent event = new PaymentPaidEvent(
                eventId(),
                EVENT_TYPE_PAYMENT_PAID,
                paymentOrder.getPaymentNo(),
                paymentOrder.getOrderId(),
                paymentOrder.getUserId(),
                paymentOrder.getAmount(),
                paymentOrder.getChannel(),
                LocalDateTime.now()
        );
        String messageId = localMessageService.createDomainEventMessage(
                LocalMessageBusinessTypeEnum.PAYMENT_PAID_EVENT.getCode(),
                paymentOrder.getPaymentNo(),
                properties.getPaymentPaidTopic(),
                orderKey(paymentOrder.getOrderId()),
                event
        );
        publishAfterCommit(messageId);
    }

    @Override
    public void publishStockChanged(Long ticketCategoryId, Long orderId, String changeType, Integer quantity) {
        if (ticketCategoryId == null || quantity == null) {
            return;
        }
        StockChangedEvent event = new StockChangedEvent(
                eventId(),
                EVENT_TYPE_STOCK_CHANGED,
                ticketCategoryId,
                orderId,
                changeType,
                quantity,
                LocalDateTime.now()
        );
        String messageId = localMessageService.createDomainEventMessage(
                LocalMessageBusinessTypeEnum.STOCK_CHANGED_EVENT.getCode(),
                String.valueOf(ticketCategoryId),
                properties.getStockChangedTopic(),
                "ticket:" + ticketCategoryId,
                event
        );
        publishAfterCommit(messageId);
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

    private String eventId() {
        return "EVT" + UUID.randomUUID().toString().replace("-", "");
    }

    private String orderKey(Long orderId) {
        return orderId == null ? "order:unknown" : "order:" + orderId;
    }
}
