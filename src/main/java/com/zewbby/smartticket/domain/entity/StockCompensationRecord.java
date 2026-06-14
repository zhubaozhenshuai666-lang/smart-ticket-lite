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
@TableName("stock_compensation_record")
public class StockCompensationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketCategoryId;

    private String requestId;

    private Long consistencyRecordId;

    private String compensationType;

    private Integer beforeRedisStock;

    private Integer afterRedisStock;

    private Integer mysqlAvailableStock;

    private Integer inFlightDeductedQuantity;

    private Integer expectedRedisAvailableStock;

    private Integer delta;

    private String status;

    private String resultMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
