package com.zewbby.smartticket.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsyncOrderTransactionMarker {

    private String requestId;

    private Long userId;

    private Long showId;

    private Long sessionId;

    private Long ticketCategoryId;

    private Integer quantity;

    private Integer stockBucketVersion;

    private Integer stockBucketNo;

    private Boolean redisDeducted;

    private Integer deductedQuantity;

    private LocalDateTime deductedAt;

    private String messageId;

    private String activityScopeKey;

    private String routingPartitionKey;

    private LocalDateTime createdAt;
}
