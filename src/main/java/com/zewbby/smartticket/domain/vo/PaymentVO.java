package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentVO {

    private Long id;

    private String paymentNo;

    private Long orderId;

    private Long userId;

    private BigDecimal amount;

    private String channel;

    private String status;

    private LocalDateTime paidAt;

    private LocalDateTime callbackAt;

    private LocalDateTime closedAt;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
