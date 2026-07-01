package com.zewbby.smartticket.service;

import com.zewbby.smartticket.mq.PaymentCompensationMessage;

public interface PaymentCompensationPublisher {

    String publish(PaymentCompensationMessage message);
}
