package com.zewbby.smartticket.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderSnapshotRecord {

    private Long showId;

    private Long sessionId;

    private Long ticketCategoryId;

    private String showTitle;

    private LocalDateTime sessionStartTime;

    private String ticketCategoryName;

    private BigDecimal ticketPrice;
}
