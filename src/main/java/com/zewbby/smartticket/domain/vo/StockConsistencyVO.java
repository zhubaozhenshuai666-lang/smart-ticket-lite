package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockConsistencyVO {

    private Long ticketCategoryId;

    private Integer mysqlTotalStock;

    private Integer mysqlAvailableStock;

    private Integer mysqlLockedStock;

    private Integer mysqlSoldStock;

    private Integer mysqlStockSum;

    private Integer redisAvailableStock;

    private Integer inFlightDeductedQuantity;

    private Integer expectedRedisAvailableStock;

    private Integer diff;

    private Long consistencyRecordId;

    private Boolean mysqlStockConsistent;

    private Boolean redisExpectedConsistent;

    private String warningMessage;
}
