package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MockPaymentRequest {

    @NotBlank(message = "支付单号不能为空")
    private String paymentNo;

    @NotNull(message = "支付结果不能为空")
    private Boolean success;

    private Long timestamp;

    private String nonce;

    private String signature;
}
