package com.zewbby.smartticket.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutMessage {

    private Long orderId;

    private String orderNo;
}
