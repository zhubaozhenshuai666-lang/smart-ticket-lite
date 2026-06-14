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
@TableName("stock_consistency_record")
public class StockConsistencyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketCategoryId;

    private Integer redisAvailableStock;

    private Integer mysqlAvailableStock;

    private Integer mysqlLockedStock;

    private Integer mysqlSoldStock;

    private Integer inFlightDeductedQuantity;

    private Integer expectedRedisAvailableStock;

    private Integer diff;

    private String status;

    private String checkType;

    private String repairStrategy;

    private String repairResult;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime repairedAt;
}
