package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InitStockRequest {

    @NotNull(message = "初始化库存不能为空")
    @Min(value = 0, message = "初始化库存不能小于0")
    private Integer availableStock;
}
