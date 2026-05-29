package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketCategoryVO {

    private Long id;

    private Long sessionId;

    private String name;

    private BigDecimal price;

    private Integer totalStock;

    private Integer availableStock;
}
