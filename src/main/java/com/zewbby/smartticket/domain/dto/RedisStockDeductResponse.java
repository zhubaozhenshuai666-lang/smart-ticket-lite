package com.zewbby.smartticket.domain.dto;

import com.zewbby.smartticket.enums.RedisStockDeductResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedisStockDeductResponse {

    private RedisStockDeductResult result;

    private Integer bucketNo;

    public boolean isSuccess() {
        return result != null && result.isSuccess();
    }
}
