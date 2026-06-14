package com.zewbby.smartticket.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AdminUpdateTicketCategoryRequest {

    @NotBlank(message = "票档名称不能为空")
    @Size(max = 64, message = "票档名称长度不能超过64个字符")
    private String categoryName;

    @NotNull(message = "票档价格不能为空")
    @DecimalMin(value = "0.01", message = "票档价格必须大于0")
    private BigDecimal price;
}
