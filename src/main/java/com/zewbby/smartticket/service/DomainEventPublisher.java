package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.entity.PaymentOrder;
import com.zewbby.smartticket.domain.entity.TicketOrder;

public interface DomainEventPublisher {

    void publishOrderCreated(TicketOrder order);

    void publishPaymentPaid(PaymentOrder paymentOrder);

    void publishStockChanged(Long ticketCategoryId, Long orderId, String changeType, Integer quantity);
}
