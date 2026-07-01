package com.zewbby.smartticket.domain.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentPaidEvent {

    private String eventId;

    private String eventType;

    private String paymentNo;

    private Long orderId;

    private Long userId;

    private BigDecimal amount;

    private String channel;

    private LocalDateTime occurredAt;
}
