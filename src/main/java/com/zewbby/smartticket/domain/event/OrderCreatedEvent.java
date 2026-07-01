package com.zewbby.smartticket.domain.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent {

    private String eventId;

    private String eventType;

    private Long orderId;

    private String orderNo;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Long ticketCategoryId;

    private Integer quantity;

    private BigDecimal totalAmount;

    private LocalDateTime occurredAt;
}
