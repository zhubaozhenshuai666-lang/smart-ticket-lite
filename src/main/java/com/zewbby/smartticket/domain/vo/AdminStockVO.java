package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminStockVO {

    private Long ticketCategoryId;

    private Integer mysqlTotalStock;

    private Integer mysqlAvailableStock;

    private Integer mysqlLockedStock;

    private Integer mysqlSoldStock;

    private Integer redisAvailableStock;

    private Boolean soldout;

    private Integer inFlightDeductedQuantity;

    private Integer expectedRedisAvailableStock;

    private Integer diff;

    private Boolean mysqlStockConsistent;

    private Boolean redisExpectedConsistent;
}
