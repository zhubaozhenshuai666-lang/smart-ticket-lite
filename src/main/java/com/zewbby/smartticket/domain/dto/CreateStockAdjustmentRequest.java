package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateStockAdjustmentRequest {

    @NotNull(message = "票档ID不能为空")
    private Long ticketCategoryId;

    @NotNull(message = "库存调整数量不能为空")
    private Integer adjustQuantity;

    @NotBlank(message = "库存调整原因不能为空")
    private String reason;
}
