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

    private LocalDateTime processingAt;

    private Boolean redisDeducted;

    private Integer deductedQuantity;

    private LocalDateTime deductedAt;

    private Boolean compensated;

    private String compensationStatus;

    private LocalDateTime compensatedAt;

    private String failReason;

    private String messageId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
