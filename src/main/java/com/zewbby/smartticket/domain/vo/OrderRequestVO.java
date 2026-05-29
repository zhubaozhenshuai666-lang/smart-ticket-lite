package com.zewbby.smartticket.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequestVO {

    private String requestId;

    private String status;

    private Long orderId;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
