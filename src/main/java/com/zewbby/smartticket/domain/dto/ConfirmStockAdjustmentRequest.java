package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmStockAdjustmentRequest {

    @NotBlank(message = "确认token不能为空")
    private String confirmToken;
}
