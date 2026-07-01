package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.PaymentService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RocketMqPaymentCompensationConsumerTest {

    @Test
    void rocketMqPaymentCompensationConsumerDelegatesToPaymentService() {
        PaymentService paymentService = mock(PaymentService.class);
        RocketMqPaymentCompensationConsumer consumer = new RocketMqPaymentCompensationConsumer(paymentService);
        PaymentCompensationMessage message = new PaymentCompensationMessage("PAY1", 10L, 1L, true, "failed", "PC1");

        consumer.onMessage(message);

        verify(paymentService).compensateMockPay("PAY1", 1L, true);
    }

    @Test
    void rocketMqPaymentCompensationConsumerIgnoresEmptyMessage() {
        PaymentService paymentService = mock(PaymentService.class);
        RocketMqPaymentCompensationConsumer consumer = new RocketMqPaymentCompensationConsumer(paymentService);

        consumer.onMessage(null);

        verify(paymentService, never()).compensateMockPay(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean()
        );
    }
}
