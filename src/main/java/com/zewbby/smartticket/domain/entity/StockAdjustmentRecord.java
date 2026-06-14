package com.zewbby.smartticket.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_adjustment_record")
public class StockAdjustmentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketCategoryId;

    private Long operatorUserId;

    private Integer adjustQuantity;

    private Integer beforeAvailableStock;

    private Integer afterAvailableStock;

    private Integer beforeRedisStock;

    private Integer afterRedisStock;

    private String reason;

    private String status;

    private String confirmToken;

    private LocalDateTime confirmedAt;

    private Boolean rollbackAvailable;

    private Long rollbackRecordId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
