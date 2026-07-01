package com.zewbby.smartticket.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCompensationMessage {

    private String paymentNo;

    private Long orderId;

    private Long userId;

    private Boolean success;

    private String reason;

    private String messageId;
}
