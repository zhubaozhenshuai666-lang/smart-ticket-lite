package com.zewbby.smartticket.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.domain.dto.MockPaymentRequest;
import com.zewbby.smartticket.domain.vo.PaymentVO;
import com.zewbby.smartticket.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentControllerTest {

    @Test
    void mockPayPassesRawBodyAndMasksSensitiveHeadersForCallbackLog() {
        PaymentService paymentService = mock(PaymentService.class);
        PaymentController controller = new PaymentController(paymentService, new ObjectMapper());
        String rawBody = """
                {"paymentNo":"PAY1","success":true,"timestamp":1760000000000,"nonce":"n1","signature":"sig"}
                """;
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer real-token");
        request.addHeader("X-Mock-Secret", "real-secret");
        request.addHeader("X-Trace-Id", "trace-1");
        when(paymentService.mockPay(any(MockPaymentRequest.class), eq(rawBody), any()))
                .thenReturn(paymentVO());

        controller.mockPay(rawBody, request);

        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(paymentService).mockPay(any(MockPaymentRequest.class), eq(rawBody), headersCaptor.capture());
        Map<String, String> headers = headersCaptor.getValue();
        /*
         * 支付回调原文日志是排查证据，不是保险柜。
         * Controller 可以把安全 header 的存在记录下来，但 token/secret/password 这类敏感值必须脱敏后再入库。
         */
        assertThat(headers.get("Authorization")).isEqualTo("***");
        assertThat(headers.get("X-Mock-Secret")).isEqualTo("***");
        assertThat(headers.get("X-Trace-Id")).isEqualTo("trace-1");
    }

    private PaymentVO paymentVO() {
        PaymentVO paymentVO = new PaymentVO();
        paymentVO.setPaymentNo("PAY1");
        paymentVO.setOrderId(10L);
        paymentVO.setUserId(1L);
        paymentVO.setAmount(new BigDecimal("880.00"));
        paymentVO.setStatus("SUCCESS");
        paymentVO.setCreatedAt(LocalDateTime.now());
        paymentVO.setUpdatedAt(LocalDateTime.now());
        return paymentVO;
    }
}
