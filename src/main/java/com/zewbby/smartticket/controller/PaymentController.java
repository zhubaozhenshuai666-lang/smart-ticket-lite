package com.zewbby.smartticket.controller;

import com.zewbby.smartticket.common.ApiResponse;
import com.zewbby.smartticket.domain.dto.CreatePaymentRequest;
import com.zewbby.smartticket.domain.dto.MockPaymentRequest;
import com.zewbby.smartticket.domain.vo.PaymentVO;
import com.zewbby.smartticket.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    private final ObjectMapper objectMapper;

    public PaymentController(PaymentService paymentService, ObjectMapper objectMapper) {
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/create")
    public ApiResponse<PaymentVO> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        return ApiResponse.success(paymentService.createPayment(request));
    }

    @PostMapping("/mock-pay")
    public ApiResponse<PaymentVO> mockPay(@RequestBody String rawBody, HttpServletRequest httpServletRequest) {
        /*
         * mock-pay 仍然是模拟支付，但它会触发订单支付成功和库存确认，不能把它当成普通用户表单。
         * Controller 保留原始 body 和脱敏 header，Service 无论验签成功还是失败都能写 payment_callback_log。
         */
        MockPaymentRequest request = parseMockPaymentRequest(rawBody);
        return ApiResponse.success(paymentService.mockPay(request, rawBody, extractSafeHeaders(httpServletRequest)));
    }

    @GetMapping("/{paymentNo}")
    public ApiResponse<PaymentVO> getPayment(@PathVariable String paymentNo) {
        return ApiResponse.success(paymentService.getPayment(paymentNo));
    }

    private MockPaymentRequest parseMockPaymentRequest(String rawBody) {
        try {
            return objectMapper.readValue(rawBody, MockPaymentRequest.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private Map<String, String> extractSafeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, maskSensitiveHeader(name, request.getHeader(name)));
        }
        return headers;
    }

    private String maskSensitiveHeader(String name, String value) {
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lowerName.contains("authorization") || lowerName.contains("token")
                || lowerName.contains("secret") || lowerName.contains("password")) {
            return "***";
        }
        return value;
    }
}
