package com.zewbby.smartticket.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AsyncCreateOrderMessage {

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

    public AsyncCreateOrderMessage(String requestId,
                                   Long userId,
                                   Long showId,
                                   Long sessionId,
                                   Long ticketCategoryId,
                                   Integer quantity) {
        this.requestId = requestId;
        this.userId = userId;
        this.showId = showId;
        this.sessionId = sessionId;
        this.ticketCategoryId = ticketCategoryId;
        this.quantity = quantity;
    }
}
