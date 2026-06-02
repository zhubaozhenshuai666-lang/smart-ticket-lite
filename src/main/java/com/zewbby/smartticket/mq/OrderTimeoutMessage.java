package com.zewbby.smartticket.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutMessage {

    private Long orderId;

    private String orderNo;

    private Long userId;

    private LocalDateTime expireTime;

    private String traceId;

    private String messageId;

    public OrderTimeoutMessage(Long orderId, String orderNo) {
        this.orderId = orderId;
        this.orderNo = orderNo;
    }
}
