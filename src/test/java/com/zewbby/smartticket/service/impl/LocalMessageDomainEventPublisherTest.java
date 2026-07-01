package com.zewbby.smartticket.service.impl;

import com.zewbby.smartticket.config.DomainEventProperties;
import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.enums.LocalMessageBusinessTypeEnum;
import com.zewbby.smartticket.service.LocalMessageService;
import com.zewbby.smartticket.task.LocalMessagePublishTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalMessageDomainEventPublisherTest {

    private LocalMessageService localMessageService;

    private LocalMessagePublishTask localMessagePublishTask;

    private LocalMessageDomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        localMessageService = mock(LocalMessageService.class);
        localMessagePublishTask = mock(LocalMessagePublishTask.class);
        DomainEventProperties properties = new DomainEventProperties();
        properties.setOrderCreatedTopic("order-created-topic");
        publisher = new LocalMessageDomainEventPublisher(localMessageService, localMessagePublishTask, properties);
    }

    @Test
    void publishOrderCreatedWritesLocalMessageAndTriggersImmediatePublish() {
        TicketOrder order = new TicketOrder();
        order.setId(100L);
        order.setOrderNo("ST100");
        order.setUserId(1L);
        order.setShowId(1L);
        order.setSessionId(1L);
        order.setTicketCategoryId(2L);
        order.setQuantity(1);
        order.setTotalAmount(new BigDecimal("880.00"));
        when(localMessageService.createDomainEventMessage(
                eq(LocalMessageBusinessTypeEnum.ORDER_CREATED_EVENT.getCode()),
                eq("100"),
                eq("order-created-topic"),
                eq("order:100"),
                any()
        )).thenReturn("MSG_EVENT");

        publisher.publishOrderCreated(order);

        verify(localMessagePublishTask).publishByMessageId("MSG_EVENT");
    }
}
