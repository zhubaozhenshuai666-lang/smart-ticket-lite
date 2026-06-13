package com.zewbby.smartticket.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class OrderSnapshot {

    private String showTitle;

    private LocalDateTime sessionStartTime;

    private String ticketCategoryName;

    private BigDecimal ticketPrice;
}
