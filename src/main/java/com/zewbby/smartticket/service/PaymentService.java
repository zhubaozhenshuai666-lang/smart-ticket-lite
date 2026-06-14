package com.zewbby.smartticket.service;

import com.zewbby.smartticket.domain.dto.CreatePaymentRequest;
import com.zewbby.smartticket.domain.dto.MockPaymentRequest;
import com.zewbby.smartticket.domain.vo.PaymentVO;

import java.util.Map;

public interface PaymentService {

    PaymentVO createPayment(CreatePaymentRequest request);

    PaymentVO getPayment(String paymentNo);

    PaymentVO mockPay(MockPaymentRequest request);

    PaymentVO mockPay(MockPaymentRequest request, String rawBody, Map<String, String> headers);

}
