package com.zewbby.smartticket.service;

import com.zewbby.smartticket.mq.OrderTimeoutMessage;

public interface OrderTimeoutMessagePublisher {

    String publish(OrderTimeoutMessage message);
}
